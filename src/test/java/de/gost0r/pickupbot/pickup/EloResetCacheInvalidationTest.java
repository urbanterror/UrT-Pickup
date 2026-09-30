package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.discord.DiscordUser;
import de.gost0r.pickupbot.ftwgl.FtwglApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EloResetCacheInvalidationTest {
    @TempDir
    Path directory;

    private PickupLogic logic;
    private Database database;
    private Connection connection;
    private Player lower;
    private Player higher;

    @BeforeEach
    void setUp() throws Exception {
        String environment = directory.resolve("elo-reset").toString();
        PickupBot bot = new PickupBot(environment, null, null, null, null, null, null, null, null);
        logic = spy(new PickupLogic(bot, mock(FtwglApi.class), null, null, null));
        doReturn(null).when(logic).getGametypeByString(anyString());
        database = spy(new Database(logic, null, null));
        logic.db = database;
        connection = DriverManager.getConnection("jdbc:sqlite:" + environment + ".pickup.db");
        execute("INSERT INTO season VALUES (11, 0, 1000)");
        execute("INSERT INTO player (userid, urtauth, elo, active) VALUES "
                + "('lower', 'lower', 1000, 'true'), ('higher', 'higher', 1100, 'true')");
        logic.currentSeason = database.getCurrentSeason();
        closeSeasonResult();
        lower = player("lower", 1000);
        higher = player("higher", 1100);

        // Freeze the command-cache clock so these regressions cannot pass by TTL expiry.
        var cacheField = PickupLogic.class.getDeclaredField("statsCommandCache");
        cacheField.setAccessible(true);
        cacheField.set(logic, new StatsCommandCache(() -> 0L));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) connection.close();
        if (database != null) database.disconnect();
    }

    @Test
    void sameSeasonResetRefreshesCachedPositionsImmediately() throws Exception {
        Season season = logic.currentSeason;
        assertPosition(lower, 2);
        assertPosition(higher, 1);
        assertPosition(lower, 2);
        verify(database).getRankForPlayer(lower);

        logic.cmdResetElo();

        assertSame(season, logic.currentSeason);
        assertEquals(11, database.getCurrentSeason().number);
        closeSeasonResult();
        assertEquals(500, storedElo("lower"));
        assertEquals(500, storedElo("higher"));
        // Position is database-backed; resetting already-hydrated Player.elo is a separate concern.
        assertPosition(lower, 1);
        assertPosition(higher, 1);
        assertPosition(lower, 1);
        verify(database, times(2)).getRankForPlayer(lower);
        verify(database, times(2)).getRankForPlayer(higher);
        verify(database).resetElo();
    }

    @Test
    void newSeasonAlreadyProtectsTheCommandCacheWithoutRevisionInvalidation() throws Exception {
        assertPosition(lower, 2);
        long revision = Player.currentSeasonStatsRevision();
        execute("UPDATE player SET elo=500");
        assertPosition(lower, 2); // The unchanged key still hits the populated cache.
        execute("INSERT INTO season VALUES (12, 1000, 2000)");
        logic.currentSeason = database.getCurrentSeason();
        closeSeasonResult();

        assertPosition(lower, 1);
        assertEquals(12, logic.currentSeason.number);
        assertEquals(revision, Player.currentSeasonStatsRevision());
        verify(database, times(2)).getRankForPlayer(lower);
        verify(database, never()).resetElo();
    }

    @Test
    void partiallyAppliedResetAlsoInvalidatesCachedPositions() throws Exception {
        execute("INSERT INTO player (userid, urtauth, elo, active) VALUES ('third', 'third', 1300, 'true')");
        execute("CREATE TRIGGER reject_second_reset BEFORE UPDATE OF elo ON player "
                + "WHEN NEW.elo=750 BEGIN SELECT RAISE(ABORT, 'reset failure'); END");
        assertPosition(lower, 3);

        // resetElo catches this SQL failure after committing its first UPDATE.
        logic.cmdResetElo();

        assertEquals(500, storedElo("lower"));
        assertEquals(500, storedElo("higher"));
        assertEquals(1300, storedElo("third"));
        assertEquals(11, logic.currentSeason.number);
        assertPosition(lower, 2);
        verify(database, times(2)).getRankForPlayer(lower);
    }

    // The legacy season reader leaves its successful ResultSet open. Release that
    // read snapshot so this test's separate connection can change the database.
    private void closeSeasonResult() throws Exception {
        database.getPreparedStatement(
                "SELECT number, startdate, enddate FROM season ORDER BY number DESC LIMIT 1;")
                .getMoreResults(Statement.CLOSE_CURRENT_RESULT);
    }

    private void assertPosition(Player player, int position) {
        String description = logic.cmdGetStats(player).getEmbed().getDescription();
        assertTrue(description.contains("#" + position + "\n"), description);
        assertTrue(description.contains("``Season " + logic.currentSeason.number + "``"), description);
    }

    private int storedElo(String auth) throws Exception {
        try (var statement = connection.prepareStatement("SELECT elo FROM player WHERE urtauth=?")) {
            statement.setString(1, auth);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    private void execute(String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static Player player(String auth, int elo) {
        DiscordUser user = mock(DiscordUser.class);
        when(user.getId()).thenReturn(auth);
        Player player = Player.detached(user, auth);
        player.setElo(elo);
        return player;
    }
}
