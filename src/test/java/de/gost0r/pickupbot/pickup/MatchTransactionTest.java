package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.discord.DiscordService;
import de.gost0r.pickupbot.discord.DiscordUser;
import de.gost0r.pickupbot.ftwgl.FtwglApi;
import de.gost0r.pickupbot.permission.PermissionService;
import de.gost0r.pickupbot.permission.PickupRoleCache;
import de.gost0r.pickupbot.pickup.server.Server;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MatchTransactionTest {
    @TempDir Path directory;

    @Test
    void matchCreationAndSavingCommitTogetherOrRollBackTogether() throws Exception {
        String prefix = directory.resolve("matches").toString();
        DiscordService discord = mock(DiscordService.class);
        PermissionService permissions = mock(PermissionService.class);
        FtwglApi ftw = mock(FtwglApi.class);
        PickupBot bot = new PickupBot(prefix, ftw, discord, permissions, mock(PickupRoleCache.class),
                Runnable::run, Runnable::run, Runnable::run, Runnable::run);
        PickupLogic logic = new PickupLogic(bot, ftw, discord, permissions, mock(PickupRoleCache.class));
        Database database = new Database(logic, discord, permissions);
        try (Connection reader = DriverManager.getConnection("jdbc:sqlite:" + prefix + ".pickup.db")) {
            Player alpha = player("1", "alpha");
            Player bravo = player("2", "bravo");
            database.createPlayer(alpha);
            database.createPlayer(bravo);
            Match match = mock(Match.class);
            Gametype gt = new Gametype("TS", 5, true, false);
            Server server = mock(Server.class);
            server.id = 1;
            when(match.getGametype()).thenReturn(gt);
            when(match.getServer()).thenReturn(server);
            when(match.getMap()).thenReturn(new GameMap("ut4_turnpike"));
            when(match.getMatchState()).thenReturn(MatchState.Live);
            when(match.getPlayerList()).thenReturn(List.of(alpha, bravo));
            when(match.getTeam(alpha)).thenReturn("red");
            when(match.getTeam(bravo)).thenReturn("blue");
            MatchStats alphaStats = new MatchStats();
            MatchStats bravoStats = new MatchStats();
            when(match.getStats(alpha)).thenReturn(alphaStats);
            when(match.getStats(bravo)).thenReturn(bravoStats);

            bravoStats.updateStatus(null); // fail after the first player's rows were inserted
            assertInstanceOf(NullPointerException.class,
                    assertThrows(MatchPersistenceException.class, () -> database.createMatch(match)).getCause());
            assertEquals(0, scalar(reader, "SELECT COUNT(*) FROM match"));
            assertEquals(0, scalar(reader, "SELECT COUNT(*) FROM score"));
            assertEquals(0, scalar(reader, "SELECT COUNT(*) FROM player_in_match"));
            bravoStats.updateStatus(MatchStats.Status.PLAYING);

            try (Statement stmt = reader.createStatement()) {
                stmt.execute("CREATE TRIGGER reject_match BEFORE INSERT ON match "
                        + "BEGIN SELECT RAISE(ABORT, 'intentional test failure'); END");
            }
            assertThrows(MatchPersistenceException.class, () -> database.createMatch(match));
            assertEquals(0, scalar(reader, "SELECT COUNT(*) FROM match"));
            try (Statement stmt = reader.createStatement()) {
                stmt.execute("DROP TRIGGER reject_match");
            }

            // Force the first writer attempt to time out; the retry must commit
            // after the competing SQLite transaction releases the write lock.
            var writerField = Database.class.getDeclaredField("matchWrites");
            writerField.setAccessible(true);
            Connection writer = (Connection) writerField.get(database);
            try (Statement stmt = writer.createStatement()) {
                stmt.execute("PRAGMA busy_timeout=20");
            }
            ExecutorService workers = Executors.newSingleThreadExecutor();
            int id;
            try (Connection competing = DriverManager.getConnection("jdbc:sqlite:" + prefix + ".pickup.db")) {
                try (Statement stmt = competing.createStatement()) {
                    stmt.execute("BEGIN IMMEDIATE");
                }
                Future<?> release = workers.submit(() -> {
                    try {
                        Thread.sleep(80);
                        try (Statement stmt = competing.createStatement()) {
                            stmt.execute("COMMIT");
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                id = database.createMatch(match);
                release.get();
            } finally {
                workers.shutdownNow();
            }
            assertTrue(id > 0);
            when(match.getID()).thenReturn(id);
            assertEquals(1, scalar(reader, "SELECT COUNT(*) FROM match"));
            assertEquals(4, scalar(reader, "SELECT COUNT(*) FROM score"));
            assertEquals(2, scalar(reader, "SELECT COUNT(*) FROM stats"));
            when(match.getMatchState()).thenReturn(MatchState.Done);
            when(match.getScoreRed()).thenReturn(10);
            when(match.getScoreBlue()).thenReturn(5);
            alphaStats.score[0].score = 6;
            database.saveMatch(match);
            assertEquals("Done", text(reader, "SELECT state FROM match WHERE ID=" + id));
            assertEquals(10, scalar(reader, "SELECT score_red FROM match WHERE ID=" + id));
            assertEquals(6, scalar(reader, "SELECT s.kills FROM score s JOIN stats st ON st.score_1=s.ID "
                    + "JOIN player_in_match pim ON pim.ID=st.pim WHERE pim.player_urtauth='alpha'"));

            when(match.getMatchState()).thenReturn(MatchState.Surrender);
            when(match.getScoreRed()).thenReturn(20);
            alphaStats.score[0].score = 12;
            bravoStats.updateStatus(null); // fail after the match and first player's scores were updated
            assertInstanceOf(NullPointerException.class,
                    assertThrows(MatchPersistenceException.class, () -> database.saveMatch(match)).getCause());
            assertEquals("Done", text(reader, "SELECT state FROM match WHERE ID=" + id));
            assertEquals(10, scalar(reader, "SELECT score_red FROM match WHERE ID=" + id));
            assertEquals(6, scalar(reader, "SELECT s.kills FROM score s JOIN stats st ON st.score_1=s.ID "
                    + "JOIN player_in_match pim ON pim.ID=st.pim WHERE pim.player_urtauth='alpha'"));
            bravoStats.updateStatus(MatchStats.Status.PLAYING);
            try (Statement stmt = reader.createStatement()) {
                stmt.execute("CREATE TRIGGER fail_score_update BEFORE UPDATE ON score "
                        + "BEGIN SELECT RAISE(ABORT, 'intentional test failure'); END");
            }
            assertThrows(MatchPersistenceException.class, () -> database.saveMatch(match));
            assertEquals("Done", text(reader, "SELECT state FROM match WHERE ID=" + id));
            assertEquals(10, scalar(reader, "SELECT score_red FROM match WHERE ID=" + id));
            assertEquals(6, scalar(reader, "SELECT s.kills FROM score s JOIN stats st ON st.score_1=s.ID "
                    + "JOIN player_in_match pim ON pim.ID=st.pim WHERE pim.player_urtauth='alpha'"));
            try (Statement stmt = reader.createStatement()) {
                stmt.execute("DROP TRIGGER fail_score_update");
            }
            writer.close(); // a lost writer connection must be reopened before the retry
            database.saveMatch(match);
            assertEquals("Surrender", text(reader, "SELECT state FROM match WHERE ID=" + id));
            assertEquals(20, scalar(reader, "SELECT score_red FROM match WHERE ID=" + id));
            assertEquals(12, scalar(reader, "SELECT s.kills FROM score s JOIN stats st ON st.score_1=s.ID "
                    + "JOIN player_in_match pim ON pim.ID=st.pim WHERE pim.player_urtauth='alpha'"));
        } finally {
            database.disconnect();
        }
    }

    private static Player player(String id, String auth) {
        DiscordUser user = mock(DiscordUser.class);
        when(user.getId()).thenReturn(id);
        Player player = mock(Player.class);
        when(player.getDiscordUser()).thenReturn(user);
        when(player.getUrtauth()).thenReturn(auth);
        when(player.getCountry()).thenReturn("US");
        return player;
    }

    private static int scalar(Connection connection, String sql) throws Exception {
        try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    private static String text(Connection connection, String sql) throws Exception {
        try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
