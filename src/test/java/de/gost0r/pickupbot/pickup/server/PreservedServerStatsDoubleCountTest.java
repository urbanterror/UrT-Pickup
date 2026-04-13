package de.gost0r.pickupbot.pickup.server;

import de.gost0r.pickupbot.pickup.Score;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void disconnect_thenRconShowsPreservedTotals_mustNotDoubleKdaInMatchStats() {
        ServerPlayer sp = new ServerPlayer();
        sp.auth = "wickedd";

        // In-game totals before a disconnect is observed
        sp.ctfstats.score = WICKEDD_KILLS;
        sp.ctfstats.deaths = WICKEDD_DEATHS;
        sp.ctfstats.assists = WICKEDD_ASSISTS;

        // Same as ServerMonitor.updatePlayers when the player drops off RCON for a tick (#34)
        sp.statsOffset.add(sp.ctfstats);

        // Reconnect path: Urban Terror sometimes keeps cumulative scores — next "players" output
        // still shows the same match totals (not reset to 0)
        sp.ctfstats.score = WICKEDD_KILLS;
        sp.ctfstats.deaths = WICKEDD_DEATHS;
        sp.ctfstats.assists = WICKEDD_ASSISTS;

        // Same hook ServerMonitor runs after each successful RCON merge for a tracked player
        sp.clearStatsOffsetIfServerSnapshotMatchesOffset();

        Score half = new Score();
        applySaveStatsMerge(sp, half);

        assertEquals(WICKEDD_KILLS, half.score, "kills must match FTW / demo, not 2×");
        assertEquals(WICKEDD_DEATHS, half.deaths, "deaths must match FTW / demo, not 2×");
        assertEquals(WICKEDD_ASSISTS, half.assists, "assists must match FTW / demo, not 2×");
    }

    /**
     * Regression test for race condition: player gets a few more kills between
     * disconnect detection and reconnect. Server kept cumulative stats but they're
     * now higher than the offset. Should still clear offset to prevent doubling.
     */
    @Test
    void disconnect_thenPlayerGetsMoreKillsBeforeReconnect_mustNotDoubleKda() {
        ServerPlayer sp = new ServerPlayer();
        sp.auth = "wickedd";

        // In-game totals before a disconnect is observed
        sp.ctfstats.score = WICKEDD_KILLS;
        sp.ctfstats.deaths = WICKEDD_DEATHS;
        sp.ctfstats.assists = WICKEDD_ASSISTS;

        // Player drops off RCON for a tick - bot thinks they disconnected
        sp.statsOffset.add(sp.ctfstats);

        // Reconnect path: player got 2 more kills while "disconnected" (RCON blip)
        // Server kept cumulative stats, now higher than what we stored
        sp.ctfstats.score = WICKEDD_KILLS + 2;
        sp.ctfstats.deaths = WICKEDD_DEATHS + 1;
        sp.ctfstats.assists = WICKEDD_ASSISTS;

        // This should detect server kept stats and clear offset
        sp.clearStatsOffsetIfServerSnapshotMatchesOffset();

        Score half = new Score();
        applySaveStatsMerge(sp, half);

        // Final stats should be server's current stats (40/26/7), not doubled (78/51/14)
        assertEquals(WICKEDD_KILLS + 2, half.score, "kills must reflect server stats, not doubled");
        assertEquals(WICKEDD_DEATHS + 1, half.deaths, "deaths must reflect server stats, not doubled");
        assertEquals(WICKEDD_ASSISTS, half.assists, "assists must reflect server stats, not doubled");
    }

    /**
     * When server actually resets stats to 0 on reconnect (original assumption),
     * the offset should be preserved so pre-disconnect stats aren't lost.
     */
    @Test
    void disconnect_thenServerResetsStatsToZero_mustPreserveOffset() {
        ServerPlayer sp = new ServerPlayer();
        sp.auth = "wickedd";

        // In-game totals before a disconnect is observed
        sp.ctfstats.score = WICKEDD_KILLS;
        sp.ctfstats.deaths = WICKEDD_DEATHS;
        sp.ctfstats.assists = WICKEDD_ASSISTS;

        // Player disconnects - stats saved to offset
        sp.statsOffset.add(sp.ctfstats);

        // Server resets stats to 0 on reconnect
        sp.ctfstats.score = 0;
        sp.ctfstats.deaths = 0;
        sp.ctfstats.assists = 0;

        // Server stats < offset, so offset should NOT be cleared
        sp.clearStatsOffsetIfServerSnapshotMatchesOffset();

        Score half = new Score();
        applySaveStatsMerge(sp, half);

        // Final stats should include the offset (pre-disconnect stats)
        assertEquals(WICKEDD_KILLS, half.score, "kills must include pre-disconnect stats");
        assertEquals(WICKEDD_DEATHS, half.deaths, "deaths must include pre-disconnect stats");
        assertEquals(WICKEDD_ASSISTS, half.assists, "assists must include pre-disconnect stats");
    }
}
