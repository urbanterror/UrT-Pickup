package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.discord.DiscordUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;

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
                stmt.execute("CREATE TABLE player_in_match(matchid INTEGER, player_userid TEXT, player_urtauth TEXT, team TEXT)");
                stmt.execute("INSERT INTO player VALUES ('1','alpha','true'),('2','bravo','true'),('3','charlie','true')");
            }
            try (PreparedStatement match = c.prepareStatement("INSERT INTO match VALUES (?, ?, 'Done', 100, 10, 5)");
                 PreparedStatement entry = c.prepareStatement("INSERT INTO player_in_match VALUES (?, ?, ?, ?)")) {
                int id = 0;
                for (int i = 0; i < 21; i++) {
                    addMatch(match, entry, ++id, "TS", "1", "alpha");
                    addMatch(match, entry, ++id, "TS", "3", "charlie");
                }
                for (int i = 0; i < 11; i++) {
                    addMatch(match, entry, ++id, "CTF", "1", "alpha");
                }
            }
            Database database = mock(Database.class, CALLS_REAL_METHODS);
            Field field = Database.class.getDeclaredField("c");
            field.setAccessible(true);
            field.set(database, c);
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
        DiscordUser user = mock(DiscordUser.class);
        when(user.getId()).thenReturn(id);
        return Player.detached(user, auth);
    }
}
