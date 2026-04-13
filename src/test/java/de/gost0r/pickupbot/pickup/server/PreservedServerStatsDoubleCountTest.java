package de.gost0r.pickupbot.pickup.server;

import de.gost0r.pickupbot.pickup.Score;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for statsOffset + RCON merge when the game server keeps cumulative
 * scores across a disconnect/reconnect (commit 06327153 assumed a reset to 0).
 *
 * <p>Ground truth from FTW API {@code GET /api/match-round/35660/stats} (match
 * #39371, player wickedd): 38 kills / 25 deaths / 7 assists. The Discord embed
 * showed 76/50/14 — exactly 2× — when offset and live RCON both held the same
 * totals.
 */
class PreservedServerStatsDoubleCountTest {

    /** Authoritative line from FTW for wickedd in that round. */
    private static final int WICKEDD_KILLS = 38;
    private static final int WICKEDD_DEATHS = 25;
    private static final int WICKEDD_ASSISTS = 7;

    /**
     * Mirrors {@link de.gost0r.pickupbot.pickup.server.ServerMonitor#saveStats} for one half
     * (fragboard fields).
     */
    private static void applySaveStatsMerge(ServerPlayer player, Score targetHalf) {
        targetHalf.score = player.statsOffset.score + player.ctfstats.score;
        targetHalf.deaths = player.statsOffset.deaths + player.ctfstats.deaths;
        targetHalf.assists = player.statsOffset.assists + player.ctfstats.assists;
    }

    @Test
    void serverPreservesStats_noDoubling() {
        ServerPlayer sp = new ServerPlayer();
        sp.auth = "wickedd";

        // Player has stats from playing
        sp.ctfstats.score = WICKEDD_KILLS;
        sp.ctfstats.deaths = WICKEDD_DEATHS;
        sp.ctfstats.assists = WICKEDD_ASSISTS;

        // Player "disconnects" (disappears from RCON) then reconnects
        // Server kept cumulative scores — stats are same or higher
        CTF_Stats serverStats = new CTF_Stats();
        serverStats.score = WICKEDD_KILLS;
        serverStats.deaths = WICKEDD_DEATHS;
        serverStats.assists = WICKEDD_ASSISTS;

        // preserveStatsIfReset should NOT add to offset (no reset detected)
        boolean wasReset = sp.preserveStatsIfReset(serverStats);
        assertFalse(wasReset, "Should detect server preserved stats");
        sp.ctfstats = serverStats; // simulate copy

        Score half = new Score();
        applySaveStatsMerge(sp, half);

        assertEquals(WICKEDD_KILLS, half.score, "kills must match FTW / demo, not 2×");
        assertEquals(WICKEDD_DEATHS, half.deaths, "deaths must match FTW / demo, not 2×");
        assertEquals(WICKEDD_ASSISTS, half.assists, "assists must match FTW / demo, not 2×");
    }

    @Test
    void serverPreservesStats_playerGotMoreKills_noDoubling() {
        ServerPlayer sp = new ServerPlayer();
        sp.auth = "wickedd";

        // Player has stats from playing
        sp.ctfstats.score = WICKEDD_KILLS;
        sp.ctfstats.deaths = WICKEDD_DEATHS;
        sp.ctfstats.assists = WICKEDD_ASSISTS;

        // Player "disconnects" then reconnects with MORE kills (server kept cumulative stats)
        CTF_Stats serverStats = new CTF_Stats();
        serverStats.score = WICKEDD_KILLS + 2;
        serverStats.deaths = WICKEDD_DEATHS + 1;
        serverStats.assists = WICKEDD_ASSISTS;

        // preserveStatsIfReset should NOT add to offset
        boolean wasReset = sp.preserveStatsIfReset(serverStats);
        assertFalse(wasReset, "Should detect server preserved stats");
        sp.ctfstats = serverStats;

        Score half = new Score();
        applySaveStatsMerge(sp, half);

        // Final stats should be server's current stats
        assertEquals(WICKEDD_KILLS + 2, half.score, "kills must reflect server stats, not doubled");
        assertEquals(WICKEDD_DEATHS + 1, half.deaths, "deaths must reflect server stats, not doubled");
        assertEquals(WICKEDD_ASSISTS, half.assists, "assists must reflect server stats, not doubled");
    }

    @Test
    void serverResetsStats_preservedToOffset() {
        ServerPlayer sp = new ServerPlayer();
        sp.auth = "wickedd";

        // Player has stats from playing
        sp.ctfstats.score = WICKEDD_KILLS;
        sp.ctfstats.deaths = WICKEDD_DEATHS;
        sp.ctfstats.assists = WICKEDD_ASSISTS;

        // Player reconnects and server reset stats to 0
        CTF_Stats serverStats = new CTF_Stats();
        serverStats.score = 0;
        serverStats.deaths = 0;
        serverStats.assists = 0;

        // preserveStatsIfReset should detect reset and preserve to offset
        boolean wasReset = sp.preserveStatsIfReset(serverStats);
        assertTrue(wasReset, "Should detect stats were reset");
        sp.ctfstats = serverStats; // simulate copy

        Score half = new Score();
        applySaveStatsMerge(sp, half);

        // Final stats should include the offset (pre-disconnect stats)
        assertEquals(WICKEDD_KILLS, half.score, "kills must include pre-disconnect stats");
        assertEquals(WICKEDD_DEATHS, half.deaths, "deaths must include pre-disconnect stats");
        assertEquals(WICKEDD_ASSISTS, half.assists, "assists must include pre-disconnect stats");
    }
}
