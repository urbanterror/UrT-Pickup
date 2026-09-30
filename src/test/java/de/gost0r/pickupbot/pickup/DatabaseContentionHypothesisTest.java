package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.discord.DiscordService;
import de.gost0r.pickupbot.discord.DiscordUser;
import de.gost0r.pickupbot.pickup.server.Server;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.Function;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * H3 mechanism tests, not an incident-latency benchmark. All SQL uses disposable databases.
 * The artificial delay is inside SQLite's step, not around a Java synchronized block.
 */
class DatabaseContentionHypothesisTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void cachedPreparedStatementParametersAreSharedAcrossWorkers() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            Database database = databaseUsing(connection);
            PreparedStatement first = database.getPreparedStatement("SELECT ?");
            PreparedStatement second = database.getPreparedStatement("SELECT ?");
            assertSame(first, second, "The cache returns one mutable statement to every worker");

            CountDownLatch firstParameterBound = new CountDownLatch(1);
            CountDownLatch secondQueryCompleted = new CountDownLatch(1);
            ExecutorService workers = workers(2);
            try {
                Future<String> firstQuery = workers.submit(() -> {
                    first.setString(1, "first-worker");
                    firstParameterBound.countDown();
                    assertTrue(secondQueryCompleted.await(5, TimeUnit.SECONDS));
                    try (ResultSet result = first.executeQuery()) {
                        assertTrue(result.next());
                        return result.getString(1);
                    }
                });
                Future<String> secondQuery = workers.submit(() -> {
                    assertTrue(firstParameterBound.await(5, TimeUnit.SECONDS));
                    second.setString(1, "second-worker");
                    try (ResultSet result = second.executeQuery()) {
                        assertTrue(result.next());
                        assertEquals("second-worker", result.getString(1));
                    }
                    secondQueryCompleted.countDown();
                    return "second-worker";
                });

                assertEquals("second-worker", secondQuery.get(5, TimeUnit.SECONDS));
                assertEquals("second-worker", firstQuery.get(5, TimeUnit.SECONDS),
                        "The second worker overwrites the first worker's bound parameter");
            } finally {
                secondQueryCompleted.countDown();
                stopWorkers(workers);
                first.close();
            }
        }
    }

    @Test
    void matchWriteTransactionDoesNotBlockSharedWalReader() throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("match-contention.db");
        try (Connection shared = DriverManager.getConnection(url);
             Connection matchWriter = DriverManager.getConnection(url);
             Connection independent = DriverManager.getConnection(url)) {
            createSchema(shared);
            assertWalMode(shared);
            assertWalMode(matchWriter);
            assertWalMode(independent);
            assertTrue(shared.getAutoCommit());
            Database database = databaseUsing(shared);
            setField(database, "matchWrites", matchWriter);
            Match match = matchWithPlayers(4);
            CountDownLatch insideSqlStep = new CountDownLatch(1);
            CountDownLatch releaseSqlStep = new CountDownLatch(1);
            CountDownLatch lookupStarted = new CountDownLatch(1);
            Function.create(matchWriter, "hold_match_write", new Function() {
                @Override
                protected void xFunc() throws SQLException {
                    insideSqlStep.countDown();
                    try {
                        if (!releaseSqlStep.await(15, TimeUnit.SECONDS)) {
                            throw new SQLException("Test did not release the SQL step");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new SQLException("Interrupted test SQL step", e);
                    }
                    result(0);
                }
            });
            execute(shared, "CREATE TRIGGER hold_score_insert BEFORE INSERT ON score "
                    + "BEGIN SELECT hold_match_write(); END");

            ExecutorService workers = workers(3);
            try {
                Future<Integer> writer = workers.submit(() -> {
                    return database.createMatch(match);
                });
                assertTrue(insideSqlStep.await(5, TimeUnit.SECONDS), "createMatch must reach the real SQL trigger");
                Future<Player> reader = workers.submit(() -> {
                    lookupStarted.countDown();
                    // A cache-miss lookup exercises real prepare/execute without Discord hydration.
                    return database.loadPlayer(null, "missing-auth", true);
                });
                assertTrue(lookupStarted.await(5, TimeUnit.SECONDS));
                assertNull(reader.get(5, TimeUnit.SECONDS), "The match writer must not block the shared read connection");
                assertEquals(0, scalar(shared, "SELECT COUNT(*) FROM match"));

                // Same file/table, while a write step and its transaction are still active.
                Future<Integer> independentRead = workers.submit(() -> scalar(independent, "SELECT COUNT(*) FROM score"));
                assertEquals(0, independentRead.get(5, TimeUnit.SECONDS).intValue());
                assertFalse(writer.isDone(), "WAL reader must finish before the held write is released");

                releaseSqlStep.countDown();
                assertEquals(1, writer.get(5, TimeUnit.SECONDS).intValue());
                assertEquals(8, scalar(shared, "SELECT COUNT(*) FROM score"));
                assertEquals(4, scalar(shared, "SELECT COUNT(*) FROM player_in_match"));
                assertEquals(4, scalar(shared, "SELECT COUNT(*) FROM stats"));
                assertEquals(18, scalar(matchWriter, "SELECT total_changes()"),
                        "Actual createMatch also inserts a durable settlement marker");
                assertTrue(matchWriter.getAutoCommit(), "createMatch must complete its transaction");
                assertEquals(8, scalar(independent, "SELECT COUNT(*) FROM score"));
            } finally {
                releaseSqlStep.countDown();
                stopWorkers(workers);
                Function.destroy(matchWriter, "hold_match_write");
            }
        }
    }

    @Test
    void idleOpenResultSetDoesNotHoldDriverMonitorAgainstAnotherThread() throws Exception {
        try (Connection shared = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            createSchema(shared);
            execute(shared, "INSERT INTO score(kills, deaths) VALUES (1, 0), (2, 0)");
            Database database = databaseUsing(shared);
            try (Statement statement = shared.createStatement();
                 ResultSet openRows = statement.executeQuery("SELECT kills FROM score ORDER BY ID")) {
                assertTrue(openRows.next());
                assertEquals(1, openRows.getInt(1));
                assertNull(inWorker(() -> database.loadPlayer(null, "missing-auth", true)));
                assertFalse(openRows.isClosed());
                assertTrue(openRows.next(), "Unrelated query must not invalidate this statement's result set");
                assertEquals(2, openRows.getInt(1));
            }
        }
    }

    @Test
    void idleUncommittedWalTransactionDoesNotHoldDriverMonitorAndIndependentReaderSeesCommittedSnapshot() throws Exception {
        String url = "jdbc:sqlite:" + temporaryDirectory.resolve("transaction-control.db");
        try (Connection shared = DriverManager.getConnection(url);
             Connection independent = DriverManager.getConnection(url)) {
            createSchema(shared);
            assertWalMode(shared);
            assertWalMode(independent);
            shared.setAutoCommit(false);
            try {
                execute(shared, "INSERT INTO score(kills, deaths) VALUES (7, 0)");
                assertEquals(1, inWorker(() -> scalar(shared, "SELECT COUNT(*) FROM score")).intValue(),
                        "Another thread on the same connection can read the uncommitted row between SQL steps");
                assertEquals(0, inWorker(() -> scalar(independent, "SELECT COUNT(*) FROM score")).intValue(),
                        "A distinct WAL reader remains available but sees only committed data");
            } finally {
                shared.rollback();
                shared.setAutoCommit(true);
            }
            assertEquals(0, scalar(shared, "SELECT COUNT(*) FROM score"));
        }
    }

    @Test
    void actualLoadPlayerHydrationPreservesAllBoostValuesWithoutUpdates() throws Exception {
        Database previousDatabase = Player.db;
        DiscordUser user = mock(DiscordUser.class);
        when(user.getId()).thenReturn("contention-hydration-user");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            createSchema(connection);
            // Complete the hydration schema locally; the contention fixtures stay minimal.
            execute(connection, "CREATE TABLE spree (player_urtauth TEXT, gametype TEXT, spree INTEGER)");
            execute(connection, "ALTER TABLE match ADD COLUMN score_red INTEGER");
            execute(connection, "ALTER TABLE match ADD COLUMN score_blue INTEGER");
            for (String column : List.of("assists", "caps", "returns", "fckills", "stopcaps", "protflag")) {
                execute(connection, "ALTER TABLE score ADD COLUMN " + column + " INTEGER");
            }
            execute(connection, "INSERT INTO player VALUES ('contention-hydration-user', 'contention-hydration-auth', "
                    + "1200, 0, 'true', 'NOT_DEFINED', 'true', 1000, 12345, 2, 3, 'true')");
            execute(connection, "CREATE TABLE boost_updates (eloboost INTEGER, mapvote INTEGER, mapban INTEGER)");
            execute(connection, "CREATE TRIGGER record_boost_update AFTER UPDATE OF eloboost, mapvote, mapban ON player "
                    + "BEGIN INSERT INTO boost_updates VALUES (new.eloboost, new.mapvote, new.mapban); END");
            Database database = databaseUsing(connection);
            DiscordService discord = mock(DiscordService.class);
            setField(database, "discordService", discord);
            PickupLogic logic = mock(PickupLogic.class);
            logic.currentSeason = new Season(1, 0, 1000);
            for (String name : List.of("TS", "CTF")) {
                Gametype gametype = mock(Gametype.class);
                when(gametype.getName()).thenReturn(name);
                when(logic.getGametypeByString(name)).thenReturn(gametype);
            }
            setField(database, "logic", logic);
            Player.db = database;

            Player loaded = database.loadPlayer(user, "contention-hydration-auth", true);

            assertNotNull(loaded);
            assertSame(user, loaded.getDiscordUser());
            assertEquals(12345, loaded.getEloBoost());
            assertEquals(2, loaded.getAdditionalMapVotes());
            assertEquals(3, loaded.getMapBans());
            verify(database, never()).updatePlayerBoost(any(Player.class));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM boost_updates"),
                    "Hydration must not execute any boost UPDATE statements");
            try (Statement statement = connection.createStatement();
                 ResultSet stored = statement.executeQuery("SELECT eloboost, mapvote, mapban FROM player")) {
                assertTrue(stored.next());
                assertEquals(12345L, stored.getLong("eloboost"));
                assertEquals(2, stored.getInt("mapvote"));
                assertEquals(3, stored.getInt("mapban"));
                assertFalse(stored.next());
            }
            verifyNoInteractions(discord);
        } finally {
            Player.db = previousDatabase;
            // Remove only this fixture's entry, including if hydration failed after construction.
            synchronized (Player.class) {
                playerCache().removeIf(player -> player.getDiscordUser() == user);
            }
        }
    }

    private static Database databaseUsing(Connection connection) throws Exception {
        // Bypass the constructor: it opens an environment-named application database and runs migrations.
        Database database = mock(Database.class, CALLS_REAL_METHODS);
        setField(database, "c", connection);
        setField(database, "preparedStmtCache", new HashMap<String, PreparedStatement>());
        return database;
    }

    private static Match matchWithPlayers(int count) {
        Match match = mock(Match.class);
        Gametype gametype = mock(Gametype.class);
        when(gametype.getName()).thenReturn("CTF");
        when(match.getGametype()).thenReturn(gametype);
        when(match.getMatchState()).thenReturn(MatchState.Live);
        when(match.getServer()).thenReturn(mock(Server.class));
        when(match.getMap()).thenReturn(new GameMap("ut4_casa"));
        List<Player> players = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Player player = mock(Player.class);
            DiscordUser user = mock(DiscordUser.class);
            when(user.getId()).thenReturn("user-" + i);
            when(player.getDiscordUser()).thenReturn(user);
            when(player.getUrtauth()).thenReturn("auth-" + i);
            when(match.getTeam(player)).thenReturn(i % 2 == 0 ? "red" : "blue");
            when(match.getStats(player)).thenReturn(new MatchStats());
            players.add(player);
        }
        when(match.getPlayerList()).thenReturn(players);
        return match;
    }

    private static void createSchema(Connection connection) throws SQLException {
        // Deliberately minimal test schema; never invoke production initTable/migrations.
        execute(connection, "PRAGMA journal_mode=WAL");
        execute(connection, "CREATE TABLE match (ID INTEGER PRIMARY KEY, state TEXT, gametype TEXT, server INTEGER, "
                + "starttime INTEGER, map TEXT, elo_red INTEGER, elo_blue INTEGER)");
        execute(connection, "CREATE TABLE match_settlement (matchid INTEGER PRIMARY KEY, settled INTEGER DEFAULT 0, teamsize INTEGER NOT NULL)");
        execute(connection, "CREATE TABLE score (ID INTEGER PRIMARY KEY, kills INTEGER, deaths INTEGER)");
        execute(connection, "CREATE TABLE player_in_match (ID INTEGER PRIMARY KEY, matchid INTEGER, "
                + "player_userid TEXT, player_urtauth TEXT, team TEXT)");
        execute(connection, "CREATE TABLE stats (pim INTEGER PRIMARY KEY, ip TEXT, score_1 INTEGER, score_2 INTEGER, status TEXT)");
        execute(connection, "CREATE TABLE player (userid TEXT, urtauth TEXT, elo INTEGER, elochange INTEGER, active TEXT, "
                + "country TEXT, enforce_ac TEXT, coins INTEGER, eloboost INTEGER, mapvote INTEGER, mapban INTEGER, proctf TEXT)");
        execute(connection, "CREATE TABLE banlist (player_userid TEXT, player_urtauth TEXT, start INTEGER, end INTEGER, "
                + "reason TEXT, pardon TEXT, forgiven INTEGER)");
    }

    private static ExecutorService workers(int count) {
        return Executors.newFixedThreadPool(count, runnable -> {
            Thread thread = new Thread(runnable, "database-contention-hypothesis");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static <T> T inWorker(Callable<T> operation) throws Exception {
        ExecutorService worker = workers(1);
        try {
            return worker.submit(operation).get(5, TimeUnit.SECONDS);
        } finally {
            stopWorkers(worker);
        }
    }

    private static void stopWorkers(ExecutorService workers) throws InterruptedException {
        workers.shutdownNow();
        assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS), "All database test workers must terminate");
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void assertWalMode(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA journal_mode")) {
            assertTrue(result.next());
            assertEquals("wal", result.getString(1));
        }
    }

    private static int scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static void setField(Database database, String name, Object value) throws Exception {
        Field field = Database.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(database, value);
    }

    @SuppressWarnings("unchecked")
    private static List<Player> playerCache() throws Exception {
        Field field = Player.class.getDeclaredField("playerList");
        field.setAccessible(true);
        return (List<Player>) field.get(null);
    }
}
