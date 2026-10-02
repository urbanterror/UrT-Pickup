package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.discord.DiscordService;
import de.gost0r.pickupbot.discord.DiscordUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.Function;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real SQLite hydration, with a latch inside a ban query after the base row is read. */
@Isolated("Replaces Player's static cache and database")
class PlayerHydrationRegressionTest {
    private static final long TIMEOUT = 10;
    private final List<Fixture> fixtures = new ArrayList<>();
    private final List<Gate> gates = new ArrayList<>();
    private ExecutorService workers;
    private Object originalCache;
    private Database originalDatabase;
    private long originalSequence;
    private Map<Object, Long> originalInvalidations;

    @BeforeEach
    void setUp() throws Exception {
        workers = Executors.newFixedThreadPool(4);
        synchronized (Player.class) {
            originalCache = field(Player.class, "playerList").get(null);
            field(Player.class, "playerList").set(null, new ArrayList<Player>());
            originalDatabase = Player.db;
            originalSequence = field(Player.class, "lifecycleSequence").getLong(null);
            originalInvalidations = new HashMap<>(invalidations());
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        gates.forEach(gate -> gate.release.countDown());
        workers.shutdownNow();
        assertTrue(workers.awaitTermination(TIMEOUT, TimeUnit.SECONDS));
        for (Fixture fixture : fixtures) {
            fixture.connection.close();
        }
        synchronized (Player.class) {
            field(Player.class, "playerList").set(null, originalCache);
            Player.db = originalDatabase;
            field(Player.class, "lifecycleSequence").setLong(null, originalSequence);
            invalidations().clear();
            invalidations().putAll(originalInvalidations);
        }
    }

    @Test
    void slowActualHydrationStaysDetachedAndDoesNotBlockAnyWarmLookup() throws Exception {
        Fixture fixture = fixture(true);
        Player.db = fixture.database;
        DiscordUser warmUser = user("warm-user");
        Player warm = new Player(warmUser, "warm-auth");
        Gate gate = holdHydration(1, fixture);
        Future<Player> cold = workers.submit(() -> Player.get(fixture.user));
        gate.awaitEntered();

        assertEquals(List.of(warm), cacheSnapshot(), "Even the base row and spree are still detached");
        assertSame(warm, workers.submit(() -> Player.get("warm-auth")).get(TIMEOUT, TimeUnit.SECONDS));
        assertSame(warm, workers.submit(() -> Player.get(user("warm-user"))).get(TIMEOUT, TimeUnit.SECONDS));
        assertSame(warm, workers.submit(() -> Player.get(warmUser, "warm-auth")).get(TIMEOUT, TimeUnit.SECONDS));
        assertFalse(cold.isDone());

        gate.release.countDown();
        Player loaded = cold.get(TIMEOUT, TimeUnit.SECONDS);
        assertHydrated(loaded, fixture);
        assertSame(loaded, Player.get("hydration-auth"));
        assertSame(fixture.user, loaded.getDiscordUser());
        verifyNoInteractions(fixture.discord);
        assertEquals(0, scalar(fixture.connection, "SELECT COUNT(*) FROM boost_updates"));
        fixture.assertResourcesClosed();
    }

    @Test
    void concurrentGetterAndDirectDatabaseLoadReturnOneCanonicalIdentity() throws Exception {
        Fixture first = fixture(true);
        Fixture second = fixture(true);
        Player.db = first.database;
        Gate gate = holdHydration(2, first, second);
        Future<Player> viaGetter = workers.submit(() -> Player.get("hydration-auth"));
        Future<Player> direct = workers.submit(() -> second.database.loadPlayer(second.user, new String("hydration-auth"), false));
        gate.awaitEntered();
        assertTrue(cacheSnapshot().isEmpty());

        gate.release.countDown();
        Player canonical = viaGetter.get(TIMEOUT, TimeUnit.SECONDS);
        assertNotNull(canonical);
        assertSame(canonical, direct.get(TIMEOUT, TimeUnit.SECONDS));
        assertSame(canonical, Player.get(user("hydration-user"), new String("hydration-auth")));
        assertEquals(1, cacheSnapshot().size());
        verify(first.discord).getUserById("hydration-user");
        verifyNoInteractions(second.discord);
        first.assertResourcesClosed();
        second.assertResourcesClosed();
    }

    @ParameterizedTest
    @ValueSource(strings = {"player", "spree", "banlist", "score"})
    void sqlFailureAtAnyHydrationStageReturnsNullWithoutCachingOrLeaking(String table) throws Exception {
        Fixture fixture = fixture(true);
        execute(fixture.connection, "DROP TABLE " + table);

        assertNull(fixture.database.loadPlayer(fixture.user));
        assertTrue(cacheSnapshot().isEmpty());
        fixture.assertResourcesClosed();
    }

    @Test
    void finalStatsQueryFailureDiscardsEvenAnOtherwiseHydratedPlayer() throws Exception {
        Fixture fixture = fixture(true);
        // Rank/WDL and TS aggregation still succeed; only the final CTF read fails.
        execute(fixture.connection, "ALTER TABLE score DROP COLUMN caps");

        assertNull(fixture.database.loadPlayer(fixture.user));
        assertTrue(cacheSnapshot().isEmpty());
        assertEquals(0, scalar(fixture.connection, "SELECT COUNT(*) FROM boost_updates"));
        fixture.assertResourcesClosed();
    }

    @Test
    void removalRejectsAnAlreadyReadActiveSnapshot() throws Exception {
        Fixture fixture = fixture(true);
        Player registered = new Player(fixture.user, "hydration-auth");
        Gate gate = holdHydration(1, fixture);
        Future<Player> stale = workers.submit(() -> fixture.database.loadPlayer(fixture.user));
        gate.awaitEntered();
        Player.remove(registered);
        gate.release.countDown();

        assertNull(stale.get(TIMEOUT, TimeUnit.SECONDS));
        assertFalse(registered.getActive());
        assertTrue(cacheSnapshot().isEmpty());
    }

    @Test
    void reregistrationWinsOverAnAlreadyReadSnapshot() throws Exception {
        Fixture fixture = fixture(true);
        Player registered = new Player(fixture.user, "hydration-auth");
        Gate gate = holdHydration(1, fixture);
        Future<Player> stale = workers.submit(() -> fixture.database.loadPlayer(fixture.user));
        gate.awaitEntered();
        Player.remove(registered);
        Player replacement = new Player(user("hydration-user"), new String("hydration-auth"));
        replacement.setElo(1777);
        gate.release.countDown();

        assertSame(replacement, stale.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(1777, replacement.getElo(), "Old hydration must not overwrite the new registration");
        assertEquals(List.of(replacement), cacheSnapshot());
    }

    @Test
    void publicRegistrationDuringColdLoadWinsWithoutRequiringRemoval() throws Exception {
        Fixture fixture = fixture(true);
        Gate gate = holdHydration(1, fixture);
        Future<Player> stale = workers.submit(() -> fixture.database.loadPlayer(fixture.user));
        gate.awaitEntered();
        Player replacement = new Player(fixture.user, "hydration-auth");
        gate.release.countDown();

        assertSame(replacement, stale.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(List.of(replacement), cacheSnapshot());
    }

    @Test
    void unrelatedRegistrationAndRemovalDoNotInvalidateSuccessfulHydration() throws Exception {
        Fixture fixture = fixture(true);
        Gate gate = holdHydration(1, fixture);
        Future<Player> loading = workers.submit(() -> fixture.database.loadPlayer(fixture.user));
        gate.awaitEntered();
        Player unrelated = new Player(user("unrelated-user"), "unrelated-auth");
        Player.remove(unrelated);
        Player remaining = new Player(user("other-user"), "other-auth");
        gate.release.countDown();

        Player loaded = loading.get(TIMEOUT, TimeUnit.SECONDS);
        assertHydrated(loaded, fixture);
        assertEquals(List.of(remaining, loaded), cacheSnapshot());
    }

    @Test
    void inactivePlayerIsAvailableOnlyThroughExplicitInactiveLookup() throws Exception {
        Fixture fixture = fixture(false);
        Player.db = fixture.database;
        Player inactive = Player.get(fixture.user, "hydration-auth");
        assertNotNull(inactive);
        assertFalse(inactive.getActive());
        assertNull(Player.get(fixture.user));
        assertNull(Player.get("hydration-auth"));
        assertSame(inactive, fixture.database.loadPlayer(fixture.user, "hydration-auth", false));
        assertEquals(1, cacheSnapshot().size());
    }

    @Test
    void activeSnapshotCannotReplaceNewerInactiveCanonicalPlayer() throws Exception {
        Fixture active = fixture(true);
        Fixture inactive = fixture(false);
        Player registered = new Player(active.user, "hydration-auth");
        Gate gate = holdHydration(1, active);
        Future<Player> stale = workers.submit(() -> active.database.loadPlayer(active.user));
        gate.awaitEntered();
        Player.remove(registered);
        Player historical = inactive.database.loadPlayer(inactive.user, "hydration-auth", false);
        assertNotNull(historical);
        gate.release.countDown();

        assertNull(stale.get(TIMEOUT, TimeUnit.SECONDS));
        assertFalse(historical.getActive());
        assertEquals(List.of(historical), cacheSnapshot());
    }

    private Fixture fixture(boolean active) throws Exception {
        Fixture fixture = new Fixture(active);
        fixtures.add(fixture);
        return fixture;
    }

    private Gate holdHydration(int count, Fixture... fixtures) throws SQLException {
        Gate gate = new Gate(count);
        gates.add(gate);
        for (Fixture fixture : fixtures) {
            Function.create(fixture.connection, "hold_hydration", new Function() {
                @Override
                protected void xFunc() throws SQLException {
                    assertFalse(Thread.holdsLock(Player.class), "SQL must execute outside the Player monitor");
                    gate.entered.countDown();
                    try {
                        if (!gate.release.await(30, TimeUnit.SECONDS)) {
                            throw new SQLException("Hydration gate was not released");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new SQLException(e);
                    }
                    result(100);
                }
            });
            execute(fixture.connection, "ALTER TABLE banlist RENAME TO stored_bans");
            execute(fixture.connection, "CREATE VIEW banlist AS SELECT player_userid, player_urtauth, "
                    + "hold_hydration() AS start, end, reason, pardon, forgiven FROM stored_bans");
        }
        return gate;
    }

    private static void assertHydrated(Player player, Fixture fixture) {
        assertNotNull(player);
        assertEquals(1234, player.getElo());
        assertEquals(12, player.getEloChange());
        assertEquals("DE", player.getCountry());
        assertEquals(4321, player.getCoins());
        assertEquals(12345, player.getEloBoost());
        assertEquals(2, player.getAdditionalMapVotes());
        assertEquals(3, player.getMapBans());
        assertFalse(player.getEnforceAC());
        assertTrue(player.getProctf());
        assertEquals(4, player.spree.get(fixture.ts).intValue());
        assertEquals(1, player.getPlayerBanListSince(0).size());
        assertSame(player, player.getLatestBan().player);
        assertEquals(20, player.stats.kills);
        assertEquals(10, player.stats.deaths);
        assertEquals(4, player.stats.assists);
        assertEquals(2.2f, player.getKdr(), 0.001f);
        assertEquals(1, player.stats.ts_wdl.win);
    }

    private static class Gate {
        final CountDownLatch entered;
        final CountDownLatch release = new CountDownLatch(1);

        Gate(int count) {
            entered = new CountDownLatch(count);
        }

        void awaitEntered() throws InterruptedException {
            assertTrue(entered.await(TIMEOUT, TimeUnit.SECONDS), "Actual ban hydration must reach the SQL gate");
        }
    }

    private static class Fixture {
        final Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        final Database database = mock(Database.class, CALLS_REAL_METHODS);
        final DiscordService discord = mock(DiscordService.class);
        final DiscordUser user = user("hydration-user");
        final Gametype ts = mock(Gametype.class);
        final AtomicInteger openStatements = new AtomicInteger();
        final AtomicInteger openResults = new AtomicInteger();

        Fixture(boolean active) throws Exception {
            execute(connection, "CREATE TABLE player (userid TEXT, urtauth TEXT, elo INTEGER, elochange INTEGER, active TEXT, "
                    + "country TEXT, enforce_ac TEXT, coins INTEGER, eloboost INTEGER, mapvote INTEGER, mapban INTEGER, proctf TEXT)");
            execute(connection, "INSERT INTO player VALUES ('hydration-user', 'hydration-auth', 1234, 12, '" + active
                    + "', 'DE', 'false', 4321, 12345, 2, 3, 'true')");
            execute(connection, "CREATE TABLE boost_updates (value INTEGER)");
            execute(connection, "CREATE TRIGGER record_update AFTER UPDATE ON player BEGIN INSERT INTO boost_updates VALUES (1); END");
            execute(connection, "CREATE TABLE spree (player_urtauth TEXT, gametype TEXT, spree INTEGER)");
            execute(connection, "INSERT INTO spree VALUES ('hydration-auth', 'TS', 4)");
            execute(connection, "CREATE TABLE banlist (player_userid TEXT, player_urtauth TEXT, start INTEGER, end INTEGER, "
                    + "reason TEXT, pardon TEXT, forgiven INTEGER)");
            execute(connection, "INSERT INTO banlist VALUES ('hydration-user', 'hydration-auth', 100, 200, 'NOSHOW', 'none', 0)");
            execute(connection, "CREATE TABLE match (id INTEGER PRIMARY KEY, gametype TEXT, state TEXT, starttime INTEGER, "
                    + "score_red INTEGER, score_blue INTEGER)");
            execute(connection, "INSERT INTO match VALUES (1, 'TS', 'Done', 500, 10, 5)");
            execute(connection, "CREATE TABLE player_in_match (id INTEGER PRIMARY KEY, matchid INTEGER, player_userid TEXT, "
                    + "player_urtauth TEXT, team TEXT)");
            execute(connection, "INSERT INTO player_in_match VALUES (1, 1, 'hydration-user', 'hydration-auth', 'red')");
            execute(connection, "CREATE TABLE stats (pim INTEGER, score_1 INTEGER, score_2 INTEGER)");
            execute(connection, "INSERT INTO stats VALUES (1, 1, 2)");
            execute(connection, "CREATE TABLE score (id INTEGER PRIMARY KEY, kills INTEGER, deaths INTEGER, assists INTEGER, "
                    + "caps INTEGER, returns INTEGER, fckills INTEGER, stopcaps INTEGER, protflag INTEGER)");
            execute(connection, "INSERT INTO score VALUES (1, 10, 5, 2, 0, 0, 0, 0, 0), (2, 10, 5, 2, 0, 0, 0, 0, 0)");

            PickupLogic logic = mock(PickupLogic.class);
            logic.currentSeason = new Season(1, 0, 1000);
            when(ts.getName()).thenReturn("TS");
            Gametype ctf = mock(Gametype.class);
            when(ctf.getName()).thenReturn("CTF");
            when(logic.getGametypeByString("TS")).thenReturn(ts);
            when(logic.getGametypeByString("CTF")).thenReturn(ctf);
            when(discord.getUserById("hydration-user")).thenReturn(user);
            field(Database.class, "logic").set(database, logic);
            field(Database.class, "discordService").set(database, discord);
            field(Database.class, "c").set(database, trackedConnection());
        }

        // Delegate every operation to SQLite; count explicit resource lifetimes and
        // assert that all real JDBC work (including close) stays outside Player.class.
        Connection trackedConnection() {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        assertFalse(Thread.holdsLock(Player.class));
                        Object value = invoke(connection, method, args);
                        if (value instanceof PreparedStatement statement) {
                            openStatements.incrementAndGet();
                            return track(statement, PreparedStatement.class, openStatements);
                        }
                        return value;
                    });
        }

        Object track(Object delegate, Class<?> type, AtomicInteger count) {
            return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
                assertFalse(Thread.holdsLock(Player.class));
                Object value = invoke(delegate, method, args);
                if (method.getName().equals("close")) {
                    count.decrementAndGet();
                } else if (value instanceof ResultSet result) {
                    openResults.incrementAndGet();
                    return track(result, ResultSet.class, openResults);
                }
                return value;
            });
        }

        void assertResourcesClosed() {
            assertEquals(0, openStatements.get(), "PreparedStatements must close on success and failure");
            assertEquals(0, openResults.get(), "ResultSets must close on success and failure");
        }
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Long> invalidations() throws Exception {
        return (Map<Object, Long>) field(Player.class, "invalidatedAt").get(null);
    }

    @SuppressWarnings("unchecked")
    private static List<Player> cacheSnapshot() throws Exception {
        synchronized (Player.class) {
            return new ArrayList<>((List<Player>) field(Player.class, "playerList").get(null));
        }
    }

    private static DiscordUser user(String id) {
        DiscordUser user = mock(DiscordUser.class);
        when(user.getId()).thenReturn(id);
        return user;
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }
}
