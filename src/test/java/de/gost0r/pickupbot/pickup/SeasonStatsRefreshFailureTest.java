package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.discord.DiscordUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Inject failures at executeQuery while otherwise exercising real SQLite reads. */
@Isolated("Temporarily replaces Player's cache and advances its stats revision")
class SeasonStatsRefreshFailureTest {
    private Connection connection;
    private Database database;
    private DiscordUser user;
    private final Season season = new Season(1, 0, 1000);
    private Object originalCache;
    private long originalRevision;
    private String failNextQuery;
    private int queries;
    private int failures;

    @BeforeEach
    void setUp() throws Exception {
        originalCache = field(Player.class, "playerList").get(null);
        field(Player.class, "playerList").set(null, new ArrayList<Player>());
        originalRevision = revision().get();
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        execute("CREATE TABLE player (userid TEXT, urtauth TEXT, elo INTEGER, elochange INTEGER, active TEXT, "
                + "country TEXT, enforce_ac TEXT, coins INTEGER, eloboost INTEGER, mapvote INTEGER, mapban INTEGER, proctf TEXT)");
        execute("INSERT INTO player VALUES ('refresh-user', 'refresh-auth', 1234, 12, 'true', "
                + "'DE', 'false', 4321, 12345, 2, 3, 'true')");
        execute("CREATE TABLE spree (player_urtauth TEXT, gametype TEXT, spree INTEGER)");
        execute("CREATE TABLE banlist (player_userid TEXT, player_urtauth TEXT, start INTEGER, end INTEGER, "
                + "reason TEXT, pardon TEXT, forgiven INTEGER)");
        execute("CREATE TABLE match (id INTEGER PRIMARY KEY, gametype TEXT, state TEXT, starttime INTEGER, "
                + "score_red INTEGER, score_blue INTEGER)");
        execute("CREATE TABLE player_in_match (id INTEGER PRIMARY KEY, matchid INTEGER, player_userid TEXT, "
                + "player_urtauth TEXT, team TEXT)");
        execute("CREATE TABLE stats (pim INTEGER, score_1 INTEGER, score_2 INTEGER)");
        execute("CREATE TABLE score (id INTEGER PRIMARY KEY, kills INTEGER, deaths INTEGER, assists INTEGER, "
                + "caps INTEGER, returns INTEGER, fckills INTEGER, stopcaps INTEGER, protflag INTEGER)");
        addMatch(1);

        user = mock(DiscordUser.class);
        when(user.getId()).thenReturn("refresh-user");
        PickupLogic logic = mock(PickupLogic.class);
        logic.currentSeason = season;
        for (String name : new String[]{"TS", "CTF"}) {
            Gametype gametype = mock(Gametype.class);
            when(gametype.getName()).thenReturn(name);
            when(logic.getGametypeByString(name)).thenReturn(gametype);
        }
        database = mock(Database.class, CALLS_REAL_METHODS);
        field(Database.class, "logic").set(database, logic);
        field(Database.class, "c").set(database, failingConnection());
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (connection != null) connection.close();
        } finally {
            field(Player.class, "playerList").set(null, originalCache);
            revision().set(originalRevision);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"WITH tablekdr", "WITH tablewdl", "SELECT SUM(CASE", "SELECT SUM(kills)",
            "SELECT COUNT(player_in_match"})
    void failedRefreshRetainsSnapshotAndNextGetterRetriesWithoutAnotherRevision(String sqlPrefix) throws Exception {
        Player player = database.loadPlayer(user);
        assertNotNull(player);
        PlayerStats previous = player.stats;
        assertEquals(1, previous.ts_wdl.getTotal());
        assertEquals(20, previous.kills);
        assertEquals(2.2f, player.getKdr(), 0.001f);

        finishPlacements();
        Player.invalidateSeasonStats();
        long invalidatedRevision = Player.currentSeasonStatsRevision();
        failNextQuery = sqlPrefix;

        // This is also the explicit refresh used by the async match-completion worker.
        assertDoesNotThrow(() -> player.refreshCurrentSeasonStats(database, season));
        assertEquals(1, failures, "The intended SQL boundary must actually fail");
        assertSame(previous, player.stats);
        assertEquals(2.2f, player.getKdr(), 0.001f, "Even a late CTF failure must not publish a partial KDR");
        assertEquals(-1L, field(Player.class, "statsRevision").getLong(player));

        PlayerStats recovered = player.getCurrentSeasonStats(database, season);
        assertNotSame(previous, recovered);
        assertEquals(5, recovered.ts_wdl.getTotal(), "The next command must see completed placements");
        assertEquals(200, recovered.kills);
        assertEquals(4.2f, player.getKdr(), 0.001f);
        assertEquals(invalidatedRevision, Player.currentSeasonStatsRevision());
        assertEquals(invalidatedRevision, field(Player.class, "statsRevision").getLong(player));
        int afterRecovery = queries;
        assertSame(recovered, player.getCurrentSeasonStats(database, season));
        assertEquals(afterRecovery, queries, "Only successful results may be reused");
    }

    @Test
    void failedForcedRefreshInvalidatesEvenAPreviouslyCurrentSnapshot() throws Exception {
        Player player = database.loadPlayer(user);
        assertNotNull(player);
        PlayerStats previous = player.stats;
        long currentRevision = Player.currentSeasonStatsRevision();
        finishPlacements();
        failNextQuery = "SELECT SUM(CASE";

        player.refreshCurrentSeasonStats(database, season);
        assertEquals(1, failures);
        assertSame(previous, player.stats);
        assertEquals(-1L, field(Player.class, "statsRevision").getLong(player));
        assertEquals(5, player.getCurrentSeasonStats(database, season).ts_wdl.getTotal());
        assertEquals(currentRevision, Player.currentSeasonStatsRevision());
    }

    @Test
    void failedFirstReadReturnsDefaultSnapshotWithoutMemoizingIt() throws Exception {
        Player player = Player.detached(user, "refresh-auth");
        PlayerStats initial = player.stats;
        failNextQuery = "SELECT SUM(kills)";

        assertSame(initial, player.getCurrentSeasonStats(database, season));
        assertEquals(1, failures);
        assertEquals(-1L, field(Player.class, "statsRevision").getLong(player));
        PlayerStats recovered = player.getCurrentSeasonStats(database, season);
        assertNotSame(initial, recovered);
        assertEquals(1, recovered.ts_wdl.getTotal());
        assertEquals(20, recovered.kills);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SELECT SUM(CASE", "SELECT COUNT(player_in_match"})
    void hydrationRemainsStrictAndSuccessfulHydrationIsImmediatelyReusable(String sqlPrefix) throws Exception {
        failNextQuery = sqlPrefix;
        assertNull(database.loadPlayer(user));
        assertEquals(1, failures);
        assertNull(Player.getCachedByDiscordId(user.getId()));

        Player hydrated = database.loadPlayer(user);
        assertNotNull(hydrated);
        assertSame(hydrated, Player.getCachedByDiscordId(user.getId()));
        int afterHydration = queries;
        assertSame(hydrated.stats, hydrated.getCurrentSeasonStats(database, season));
        assertEquals(afterHydration, queries);
        assertEquals(20, hydrated.stats.kills);
        assertEquals(2.2f, hydrated.getKdr(), 0.001f);
    }

    @Test
    void historicalDisplayStillReturnsBestEffortStats() throws Exception {
        Player player = Player.detached(user, "refresh-auth");
        failNextQuery = "SELECT COUNT(player_in_match";

        PlayerStats partial = assertDoesNotThrow(() -> database.getPlayerStats(player, season));
        assertEquals(1, failures);
        assertEquals(1, partial.ts_wdl.getTotal());
        assertEquals(20, partial.kills);
        assertEquals(-1L, field(Player.class, "statsRevision").getLong(player));
    }

    @Test
    void unstubbedDatabaseMockCannotPublishNullOrMarkStatsCurrent() throws Exception {
        Database unavailable = mock(Database.class);
        Player player = Player.detached(user, "refresh-auth");
        PlayerStats initial = player.stats;
        assertSame(initial, player.getCurrentSeasonStats(unavailable, season));
        assertEquals(-1L, field(Player.class, "statsRevision").getLong(player));

        PlayerStats complete = new PlayerStats();
        when(unavailable.tryGetPlayerStats(player, season)).thenReturn(complete);
        assertSame(complete, player.getCurrentSeasonStats(unavailable, season));
        assertSame(complete, player.getCurrentSeasonStats(unavailable, season));
        verify(unavailable, times(2)).tryGetPlayerStats(player, season);
    }

    private void finishPlacements() throws SQLException {
        for (int id = 2; id <= 5; id++) addMatch(id);
        execute("UPDATE score SET kills=20");
    }

    private void addMatch(int id) throws SQLException {
        execute("INSERT INTO match VALUES (" + id + ", 'TS', 'Done', 500, 10, 5)");
        execute("INSERT INTO player_in_match VALUES (" + id + ", " + id
                + ", 'refresh-user', 'refresh-auth', 'red')");
        execute("INSERT INTO stats VALUES (" + id + ", " + (id * 2 - 1) + ", " + (id * 2) + ")");
        for (int scoreId = id * 2 - 1; scoreId <= id * 2; scoreId++) {
            execute("INSERT INTO score VALUES (" + scoreId + ", 10, 5, 2, 0, 0, 0, 0, 0)");
        }
    }

    private Connection failingConnection() {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(connection, args);
                        if (!method.getName().equals("prepareStatement")) return result;
                        String sql = (String) args[0];
                        PreparedStatement statement = (PreparedStatement) result;
                        return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                new Class<?>[]{PreparedStatement.class}, (ignored, operation, parameters) -> {
                                    if (operation.getName().equals("executeQuery")) {
                                        queries++;
                                        if (failNextQuery != null && sql.startsWith(failNextQuery)) {
                                            failNextQuery = null;
                                            failures++;
                                            throw new SQLException("Injected one-shot stats read failure");
                                        }
                                    }
                                    try {
                                        return operation.invoke(statement, parameters);
                                    } catch (InvocationTargetException e) {
                                        throw e.getCause();
                                    }
                                });
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static AtomicLong revision() throws Exception {
        return (AtomicLong) field(Player.class, "seasonStatsRevision").get(null);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
