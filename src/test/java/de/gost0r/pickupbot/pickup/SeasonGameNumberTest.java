package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.discord.DiscordService;
import de.gost0r.pickupbot.discord.DiscordChannel;
import de.gost0r.pickupbot.ftwgl.FtwglApi;
import de.gost0r.pickupbot.permission.PermissionService;
import de.gost0r.pickupbot.permission.PickupRoleCache;
import de.gost0r.pickupbot.pickup.server.Server;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SeasonGameNumberTest {
    @TempDir Path directory;

    @Test
    void migratesHistoryByExactGametypeAndSeasonAndKeepsNumbersAcrossRestarts() throws Exception {
        String prefix = directory.resolve("legacy").toString();
        try (Connection connection = connect(prefix); Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("CREATE TABLE season (number INTEGER PRIMARY KEY, startdate INTEGER, enddate INTEGER)");
            stmt.executeUpdate("INSERT INTO season VALUES (10, 100, 200), (11, 200, 300)");
            stmt.executeUpdate("CREATE TABLE match (ID INTEGER PRIMARY KEY AUTOINCREMENT, server INTEGER, "
                    + "gametype TEXT, state TEXT, starttime INTEGER, map TEXT, elo_red INTEGER, elo_blue INTEGER, "
                    + "score_red INTEGER DEFAULT 0, score_blue INTEGER DEFAULT 0)");
            stmt.executeUpdate("INSERT INTO match (ID, server, gametype, state, starttime, map) VALUES "
                    + "(10, 1, 'TS', 'Done', 150, 'ut4_casa'),"
                    + "(20, 1, 'CTF', 'Done', 200, 'ut4_casa'),"
                    + "(30, 1, 'TS', 'Abort', 210, 'ut4_casa'),"
                    + "(40, 1, 'PROMOD', 'Done', 220, 'ut4_casa'),"
                    + "(50, 1, 'TS', 'Live', 230, 'ut4_casa'),"
                    + "(60, 1, 'TS', 'Done', 300, 'ut4_casa')");
        }

        PickupLogic logic = logic(prefix);
        Database database = database(logic);
        try {
            assertEquals(new SeasonGameNumber(10, 1), database.loadMatchSummary(10).seasonGameNumber);
            assertEquals(new SeasonGameNumber(11, 1), database.loadMatchSummary(20).seasonGameNumber);
            assertEquals(new SeasonGameNumber(11, 1), database.loadMatchSummary(30).seasonGameNumber);
            assertEquals(new SeasonGameNumber(11, 1), database.loadMatchSummary(40).seasonGameNumber);
            assertEquals(new SeasonGameNumber(11, 2), database.loadMatchSummary(50).seasonGameNumber);
            assertNull(database.loadMatchSummary(60).seasonGameNumber);

            Gametype ts = new Gametype("TS", 1, true, false);
            Server server = mock(Server.class);
            when(logic.getGametypeByString("TS")).thenReturn(ts);
            when(logic.getServerByID(1)).thenReturn(server);
            when(logic.getMapByName("ut4_casa")).thenReturn(new GameMap("ut4_casa"));
            Match restored = database.loadMatch(50);
            assertEquals(new SeasonGameNumber(11, 2), restored.getSeasonGameNumber());
            assertTrue(restored.getMatchInfo().contains("Season 11: TS GAME #2"));
            assertTrue(restored.getMatchInfo().contains("#50"));
            assertTrue(restored.getMatchEmbed(false).getTitle().contains("Match #50 · Season 11: TS GAME #2"));
            restored.getServer().region = Region.EU;
            assertEquals("**TS: Match #50 · Season 11: TS GAME #2** :flag_eu: (avg ELO: 0)",
                    restored.buildStartAnnouncementHead());
            assertTrue(restored.insertMatchNumber(Config.pkup_go_player)
                    .startsWith("**Match #50 · Season 11: TS GAME #2**"));

            PickupBot originalBot = logic.bot;
            PickupBot bot = mock(PickupBot.class);
            logic.bot = bot;
            Method sendAftermath = Match.class.getDeclaredMethod("sendAftermath");
            sendAftermath.setAccessible(true);
            sendAftermath.invoke(restored);
            var resultText = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(bot).sendMsg(anyList(), resultText.capture(), any());
            assertTrue(resultText.getValue().startsWith("**TS**: Aftermath #50 · Season 11: TS GAME #2 (ut4_casa):"));
            logic.bot = originalBot;

            PickupLogic displayLogic = displayLogic(logic.bot, database, ts);
            setField(displayLogic, "ongoingMatches", List.of(restored));
            PickupReply liveReply = displayLogic.cmdLive(mock(DiscordChannel.class)).getFirst();
            assertTrue(liveReply.getMessage().contains("Season 11: TS GAME #2"));
            setField(displayLogic, "ongoingMatches", List.of());
            PickupReply historicalReply = displayLogic.cmdDisplayMatch("50");
            assertTrue(historicalReply.getEmbed().getTitle().contains("Match #50 · Season 11: TS GAME #2"));
        } finally {
            database.disconnect();
        }

        // A later season-date edit must not rewrite a match's identity.
        try (Connection connection = connect(prefix); Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("UPDATE season SET startdate=240 WHERE number=11");
        }
        Database reopened = database(logic);
        try {
            assertEquals(new SeasonGameNumber(11, 2), reopened.loadMatchSummary(50).seasonGameNumber);
            Match next = match(logic, "TS", 250);
            int id = reopened.createMatch(next);
            assertEquals(new SeasonGameNumber(11, 3), next.getSeasonGameNumber());
            assertEquals(next.getSeasonGameNumber(), reopened.loadMatchSummary(id).seasonGameNumber);
        } finally {
            reopened.disconnect();
        }
    }

    @Test
    void assignsIndependentSequencesAndRollsBackNumberWithFailedMatchCreation() throws Exception {
        String prefix = directory.resolve("creation").toString();
        PickupLogic logic = logic(prefix);
        Database database = database(logic);
        try (Connection connection = connect(prefix); Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("INSERT INTO season VALUES (11, 100, 200), (12, 200, 300)");
            Match first = match(logic, "TS", 100);
            database.createMatch(first);
            assertEquals(new SeasonGameNumber(11, 1), first.getSeasonGameNumber());
            Match ctf = match(logic, "CTF", 110);
            database.createMatch(ctf);
            assertEquals(new SeasonGameNumber(11, 1), ctf.getSeasonGameNumber());

            stmt.executeUpdate("CREATE TRIGGER reject_settlement BEFORE INSERT ON match_settlement "
                    + "BEGIN SELECT RAISE(ABORT, 'test rollback after numbering'); END");
            Match failed = match(logic, "TS", 120);
            assertThrows(MatchPersistenceException.class, () -> database.createMatch(failed));
            assertNull(failed.getSeasonGameNumber());
            stmt.executeUpdate("DROP TRIGGER reject_settlement");
            int id = database.createMatch(failed);
            assertEquals(new SeasonGameNumber(11, 2), failed.getSeasonGameNumber());
            assertEquals(failed.getSeasonGameNumber(), database.loadMatchSummary(id).seasonGameNumber);

            Match newSeason = match(logic, "TS", 200);
            database.createMatch(newSeason);
            assertEquals(new SeasonGameNumber(12, 1), newSeason.getSeasonGameNumber());
            Match outsideSeason = match(logic, "TS", 300);
            database.createMatch(outsideSeason);
            assertNull(outsideSeason.getSeasonGameNumber());
        } finally {
            database.disconnect();
        }
    }

    @Test
    void simultaneousWritersAllocateDifferentNumbers() throws Exception {
        String prefix = directory.resolve("concurrent").toString();
        PickupLogic logic = logic(prefix);
        Database first = database(logic);
        Database second = database(logic);
        try (Connection connection = connect(prefix); Statement stmt = connection.createStatement();
             var workers = Executors.newFixedThreadPool(2)) {
            stmt.executeUpdate("INSERT INTO season VALUES (11, 100, 300)");
            Match a = match(logic, "TS", 150);
            Match b = match(logic, "TS", 150);
            var aId = workers.submit(() -> first.createMatch(a));
            var bId = workers.submit(() -> second.createMatch(b));
            assertNotEquals(aId.get(), bId.get());
            assertEquals(11, a.getSeasonGameNumber().season());
            assertEquals(11, b.getSeasonGameNumber().season());
            assertEquals(List.of(1, 2), List.of(a.getSeasonGameNumber().gameNumber(),
                    b.getSeasonGameNumber().gameNumber()).stream().sorted().toList());
        } finally {
            first.disconnect();
            second.disconnect();
        }
    }

    @Test
    void failedHistoricalNumberingMigrationRollsBackSchemaAndRetries() throws Exception {
        String prefix = directory.resolve("migration-retry").toString();
        try (Connection connection = connect(prefix); Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("CREATE TABLE season (number INTEGER PRIMARY KEY, startdate INTEGER, enddate INTEGER)");
            stmt.executeUpdate("INSERT INTO season VALUES (11, 100, 300)");
            stmt.executeUpdate("CREATE TABLE match (ID INTEGER PRIMARY KEY AUTOINCREMENT, server INTEGER, "
                    + "gametype TEXT, state TEXT, starttime INTEGER, map TEXT, elo_red INTEGER, elo_blue INTEGER, "
                    + "score_red INTEGER DEFAULT 0, score_blue INTEGER DEFAULT 0)");
            stmt.executeUpdate("INSERT INTO match (ID, server, gametype, state, starttime, map) "
                    + "VALUES (10, 1, 'TS', 'Done', 150, 'ut4_casa')");
            stmt.executeUpdate("CREATE TRIGGER reject_numbering BEFORE UPDATE OF season_number ON match "
                    + "BEGIN SELECT RAISE(ABORT, 'intentional migration failure'); END");
        }

        PickupLogic logic = logic(prefix);
        Database failedAttempt = database(logic);
        failedAttempt.disconnect();
        try (Connection connection = connect(prefix)) {
            assertFalse(hasColumn(connection, "match", "season_number"), "failed migration must roll back ALTER TABLE");
            assertFalse(hasColumn(connection, "match", "season_game_number"), "failed migration must roll back both columns");
            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate("DROP TRIGGER reject_numbering");
            }
        }

        Database retry = database(logic);
        try {
            assertEquals(new SeasonGameNumber(11, 1), retry.loadMatchSummary(10).seasonGameNumber);
        } finally {
            retry.disconnect();
        }
    }

    private PickupLogic displayLogic(PickupBot bot, Database database, Gametype ts) throws Exception {
        PickupLogic displayLogic = new PickupLogic(bot, mock(FtwglApi.class), mock(DiscordService.class),
                mock(PermissionService.class), mock(PickupRoleCache.class));
        displayLogic.db = database;
        setField(displayLogic, "serverList", List.of());
        setField(displayLogic, "mapList", List.of(new GameMap("ut4_casa")));
        Map<Gametype, Match> currentMatches = new java.util.HashMap<>();
        currentMatches.put(ts, null);
        setField(displayLogic, "curMatch", currentMatches);
        return displayLogic;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = PickupLogic.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private PickupLogic logic(String prefix) {
        DiscordService discord = mock(DiscordService.class);
        PermissionService permissions = mock(PermissionService.class);
        FtwglApi ftw = mock(FtwglApi.class);
        PickupBot bot = new PickupBot(prefix, ftw, discord, permissions, mock(PickupRoleCache.class),
                Runnable::run, Runnable::run, Runnable::run, Runnable::run);
        PickupLogic logic = mock(PickupLogic.class);
        logic.bot = bot;
        return logic;
    }

    private Database database(PickupLogic logic) {
        return new Database(logic, mock(DiscordService.class), mock(PermissionService.class));
    }

    private Match match(PickupLogic logic, String mode, long startTime) {
        Server server = mock(Server.class);
        server.id = 1;
        return new Match(0, startTime, new GameMap("ut4_casa"), new int[]{0, 0}, new int[]{1000, 1000},
                Map.of("red", List.of(), "blue", List.of()), MatchState.Live,
                new Gametype(mode, 1, true, false), server, Map.of(), logic, mock(PermissionService.class));
    }

    private Connection connect(String prefix) throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + prefix + ".pickup.db");
    }

    private static boolean hasColumn(Connection connection, String table, String column) throws Exception {
        try (Statement stmt = connection.createStatement(); var rows = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rows.next()) if (column.equals(rows.getString("name"))) return true;
        }
        return false;
    }
}
