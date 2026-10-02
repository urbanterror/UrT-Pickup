package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.discord.DiscordUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class WdlRankQueryTest {
    @TempDir Path directory;

    @Test
    void winRateRankingPreservesTiesAndModeThresholds() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("rank.db"))) {
            try (Statement stmt = c.createStatement()) {
                stmt.execute("CREATE TABLE player(userid TEXT, urtauth TEXT, active TEXT)");
                stmt.execute("CREATE TABLE match(id INTEGER PRIMARY KEY, gametype TEXT, state TEXT, starttime INTEGER, score_red INTEGER, score_blue INTEGER)");
                stmt.execute("CREATE TABLE player_in_match(ID INTEGER PRIMARY KEY, matchid INTEGER, player_userid TEXT, player_urtauth TEXT, team TEXT)");
                stmt.execute("CREATE TABLE score(ID INTEGER PRIMARY KEY, kills INTEGER, deaths INTEGER, assists INTEGER)");
                stmt.execute("CREATE TABLE stats(pim INTEGER, score_1 INTEGER, score_2 INTEGER)");
                stmt.execute("INSERT INTO player VALUES ('1','alpha','true'),('2','bravo','true'),('3','charlie','true')");
            }
            try (PreparedStatement match = c.prepareStatement("INSERT INTO match VALUES (?, ?, 'Done', 100, 10, 5)");
                 PreparedStatement entry = c.prepareStatement("INSERT INTO player_in_match(matchid, player_userid, player_urtauth, team) VALUES (?, ?, ?, ?)")) {
                int id = 0;
                for (int i = 0; i < 21; i++) {
                    addMatch(match, entry, ++id, "TS", "1", "alpha");
                    addMatch(match, entry, ++id, "TS", "3", "charlie");
                }
                for (int i = 0; i < 11; i++) {
                    addMatch(match, entry, ++id, "CTF", "1", "alpha");
                }
            }
            try (Statement stmt = c.createStatement()) {
                stmt.execute("INSERT INTO score SELECT ID*2, CASE WHEN team='red' THEN 2 ELSE 1 END, 1, 0 FROM player_in_match");
                stmt.execute("INSERT INTO score SELECT ID*2+1, CASE WHEN team='red' THEN 2 ELSE 1 END, 1, 0 FROM player_in_match");
                stmt.execute("INSERT INTO stats SELECT ID, ID*2, ID*2+1 FROM player_in_match");
            }
            Database database = mock(Database.class, CALLS_REAL_METHODS);
            Field field = Database.class.getDeclaredField("c");
            field.setAccessible(true);
            Connection monitored = spy(c);
            field.set(database, monitored);
            field = Database.class.getDeclaredField("preparedStmtCache");
            field.setAccessible(true);
            field.set(database, new HashMap<String, PreparedStatement>());
            Season season = new Season(11, 0, 1000);
            Player alpha = player("1", "alpha");
            Player bravo = player("2", "bravo");
            Player charlie = player("3", "charlie");
            Gametype ts = new Gametype("TS", 5, true, false);
            Gametype ctf = new Gametype("CTF", 5, true, false);

            assertEquals(1, database.getWDLRankForPlayer(alpha, ts, season));
            assertEquals(1, database.getWDLRankForPlayer(charlie, ts, season));
            assertEquals(3, database.getWDLRankForPlayer(bravo, ts, season));
            assertEquals(1, database.getWDLRankForPlayer(alpha, ctf, season));
            assertEquals(2, database.getWDLRankForPlayer(bravo, ctf, season));
            assertEquals(-1, database.getWDLRankForPlayer(charlie, ctf, season));
            verify(monitored, times(2)).prepareStatement(contains("WITH tablewdl")); // one query per mode, not player

            assertEquals(1, database.getKDRRankForPlayer(alpha, ts, season));
            assertEquals(1, database.getKDRRankForPlayer(charlie, ts, season));
            assertEquals(3, database.getKDRRankForPlayer(bravo, ts, season));
            assertEquals(1, database.getKDRRankForPlayer(alpha, ctf, season));
            assertEquals(2, database.getKDRRankForPlayer(bravo, ctf, season));
            verify(monitored, times(2)).prepareStatement(contains("WITH tablekdr"));

            try (MockedStatic<Player> lookups = mockStatic(Player.class)) {
                lookups.when(() -> Player.get("alpha")).thenReturn(alpha);
                lookups.when(() -> Player.get("bravo")).thenReturn(bravo);
                lookups.when(() -> Player.get("charlie")).thenReturn(charlie);
                Map<Player, String> tsTop = database.getTopWDL(10, ts, season);
                assertEquals(3, tsTop.size());
                assertEquals("100%  (*21*)", tsTop.get(alpha));
                assertEquals("100%  (*21*)", tsTop.get(charlie));
                assertEquals("0%  (*42*)", tsTop.get(bravo));
                Map<Player, String> ctfTop = database.getTopWDL(10, ctf, season);
                assertEquals("100%  (*11*)", ctfTop.get(alpha));
                assertEquals("0%  (*11*)", ctfTop.get(bravo));
            }

            try (Statement stmt = c.createStatement()) {
                stmt.execute("UPDATE player SET active='false' WHERE userid='3'");
            }
            Player.invalidateSeasonStats();
            assertEquals(-1, database.getWDLRankForPlayer(charlie, ts, season));
            assertEquals(2, database.getWDLRankForPlayer(bravo, ts, season));
            assertEquals(-1, database.getKDRRankForPlayer(charlie, ts, season));
            assertEquals(2, database.getKDRRankForPlayer(bravo, ts, season));
            verify(monitored, times(3)).prepareStatement(contains("WITH tablewdl"));
            verify(monitored, times(3)).prepareStatement(contains("WITH tablekdr"));
            assertEquals(-1, database.getWDLRankForPlayer(alpha, ts, new Season(11, 101, 1000)));
            Season allTime = new Season(0, 0, 1000);
            assertEquals(-1, database.getWDLRankForPlayer(alpha, ts, allTime)); // requires >100 TS matches
            assertEquals(-1, database.getKDRRankForPlayer(alpha, ts, allTime));
            assertEquals(1, database.getWDLRankForPlayer(alpha, ctf, allTime)); // CTF still requires >10
            assertEquals(1, database.getKDRRankForPlayer(alpha, ctf, allTime));

            // Preserve the previous COUNT(metric > target) behavior: a qualifying
            // player whose metric is NULL has rank 1, not last place.
            try (Statement stmt = c.createStatement()) {
                stmt.execute("INSERT INTO player VALUES ('4','delta','true')");
                for (int id = 100; id < 121; id++) {
                    stmt.execute("INSERT INTO match VALUES (" + id + ", 'TS', 'Done', 100, NULL, NULL)");
                    stmt.execute("INSERT INTO player_in_match(matchid, player_userid, player_urtauth, team) "
                            + "VALUES (" + id + ", '4', 'delta', 'red')");
                    stmt.execute("INSERT INTO score VALUES (" + (10_000 + id * 2) + ", 0, 0, 0)");
                    stmt.execute("INSERT INTO score VALUES (" + (10_000 + id * 2 + 1) + ", 0, 0, 0)");
                    stmt.execute("INSERT INTO stats SELECT ID, " + (10_000 + id * 2) + ", " + (10_000 + id * 2 + 1)
                            + " FROM player_in_match WHERE matchid=" + id);
                }
            }
            Player.invalidateSeasonStats();
            Player delta = player("4", "delta");
            assertEquals(1, database.getWDLRankForPlayer(delta, ts, season));
            assertEquals(1, database.getKDRRankForPlayer(delta, ts, season));
        }
    }

    private static void addMatch(PreparedStatement match, PreparedStatement entry, int id, String mode, String winnerId, String winnerAuth) throws Exception {
        match.setInt(1, id);
        match.setString(2, mode);
        match.executeUpdate();
        entry.setInt(1, id);
        entry.setString(2, winnerId);
        entry.setString(3, winnerAuth);
        entry.setString(4, "red");
        entry.executeUpdate();
        entry.setString(2, "2");
        entry.setString(3, "bravo");
        entry.setString(4, "blue");
        entry.executeUpdate();
    }

    private static Player player(String id, String auth) {
        return Player.detached(user(id), auth);
    }

    private static DiscordUser user(String id) {
        DiscordUser user = mock(DiscordUser.class);
        when(user.getId()).thenReturn(id);
        return user;
    }
}
