package de.gost0r.pickupbot.pickup.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the statsOffset mechanism that preserves player stats
 * across disconnect/reconnect cycles during a live match.
 *
 * When a player disconnects, the game server resets their scores to 0
 * on reconnect. The statsOffset field on ServerPlayer accumulates the
 * pre-disconnect stats so saveStats() can produce correct totals.
 */
class StatsOffsetTest {

    // ========== CTF_Stats.add() ==========

    @Test
    void add_accumulatesAllFields() {
        CTF_Stats base = new CTF_Stats();
        base.score = 10;
        base.deaths = 5;
        base.assists = 3;
        base.caps = 2;
        base.returns = 4;
        base.fc_kills = 1;
        base.stop_caps = 1;
        base.protect_flag = 2;

        CTF_Stats incoming = new CTF_Stats();
        incoming.score = 7;
        incoming.deaths = 3;
        incoming.assists = 2;
        incoming.caps = 1;
        incoming.returns = 1;
        incoming.fc_kills = 0;
        incoming.stop_caps = 1;
        incoming.protect_flag = 0;

        base.add(incoming);

        assertEquals(17, base.score);
        assertEquals(8, base.deaths);
        assertEquals(5, base.assists);
        assertEquals(3, base.caps);
        assertEquals(5, base.returns);
        assertEquals(1, base.fc_kills);
        assertEquals(2, base.stop_caps);
        assertEquals(2, base.protect_flag);
    }

    @Test
    void add_withZeros_leavesOriginalUnchanged() {
        CTF_Stats base = new CTF_Stats();
        base.score = 10;
        base.deaths = 5;

        CTF_Stats zeros = new CTF_Stats();
        base.add(zeros);

        assertEquals(10, base.score);
        assertEquals(5, base.deaths);
    }

    // ========== ServerPlayer statsOffset ==========

    @Test
    void serverPlayer_statsOffset_initializedToZero() {
        ServerPlayer sp = new ServerPlayer();
        assertNotNull(sp.statsOffset);
        assertEquals(0, sp.statsOffset.score);
        assertEquals(0, sp.statsOffset.deaths);
        assertEquals(0, sp.statsOffset.assists);
        assertEquals(0, sp.statsOffset.caps);
        assertEquals(0, sp.statsOffset.returns);
        assertEquals(0, sp.statsOffset.fc_kills);
        assertEquals(0, sp.statsOffset.stop_caps);
        assertEquals(0, sp.statsOffset.protect_flag);
    }

    @Test
    void copy_doesNotOverwriteStatsOffset() {
        ServerPlayer tracked = new ServerPlayer();
        tracked.auth = "playerA";
        tracked.statsOffset.score = 15;
        tracked.statsOffset.deaths = 8;

        ServerPlayer fromServer = new ServerPlayer();
        fromServer.state = ServerPlayer.ServerPlayerState.Connected;
        fromServer.id = "1";
        fromServer.name = "PlayerA";
        fromServer.team = "red";
        fromServer.ping = "50";
        fromServer.ip = "1.2.3.4";
        fromServer.auth = "playerA";
        fromServer.ctfstats.score = 0; // server reset after reconnect

        tracked.copy(fromServer);

        // ctfstats should be overwritten (fresh from server)
        assertEquals(0, tracked.ctfstats.score);
        // statsOffset should be preserved (not touched by copy)
        assertEquals(15, tracked.statsOffset.score);
        assertEquals(8, tracked.statsOffset.deaths);
    }

    // ========== Disconnect/reconnect simulation ==========

    @Test
    void singleDisconnectReconnect_statsPreserved() {
        ServerPlayer sp = new ServerPlayer();
        sp.auth = "alpha";

        // Player plays and accumulates stats
        sp.ctfstats.score = 12;
        sp.ctfstats.deaths = 4;
        sp.ctfstats.assists = 3;
        sp.ctfstats.caps = 2;
        sp.ctfstats.returns = 1;

        // Player disconnects — accumulate into offset
        sp.statsOffset.add(sp.ctfstats);

        // Player reconnects — server resets their stats to 0
        sp.ctfstats = new CTF_Stats();
        sp.ctfstats.score = 0;
        sp.ctfstats.deaths = 0;

        // Effective total = offset + current
        assertEquals(12, sp.statsOffset.score + sp.ctfstats.score);
        assertEquals(4, sp.statsOffset.deaths + sp.ctfstats.deaths);
        assertEquals(3, sp.statsOffset.assists + sp.ctfstats.assists);
        assertEquals(2, sp.statsOffset.caps + sp.ctfstats.caps);
        assertEquals(1, sp.statsOffset.returns + sp.ctfstats.returns);

        // Player continues playing and gets more kills
        sp.ctfstats.score = 5;
        sp.ctfstats.deaths = 2;
        sp.ctfstats.assists = 1;

        // Effective total
        assertEquals(17, sp.statsOffset.score + sp.ctfstats.score);
        assertEquals(6, sp.statsOffset.deaths + sp.ctfstats.deaths);
        assertEquals(4, sp.statsOffset.assists + sp.ctfstats.assists);
    }

    @Test
    void multipleDisconnectReconnect_statsAccumulate() {
        ServerPlayer sp = new ServerPlayer();
        sp.auth = "bravo";

        // --- First session ---
        sp.ctfstats.score = 10;
        sp.ctfstats.deaths = 3;
        sp.ctfstats.assists = 2;
        sp.ctfstats.caps = 1;

        // Disconnect #1
        sp.statsOffset.add(sp.ctfstats);
        assertEquals(10, sp.statsOffset.score);

        // Reconnect #1 — server resets
        sp.ctfstats = new CTF_Stats();

        // --- Second session ---
        sp.ctfstats.score = 7;
        sp.ctfstats.deaths = 2;
        sp.ctfstats.caps = 1;

        // Disconnect #2
        sp.statsOffset.add(sp.ctfstats);
        assertEquals(17, sp.statsOffset.score);
        assertEquals(5, sp.statsOffset.deaths);
        assertEquals(2, sp.statsOffset.caps);

        // Reconnect #2 — server resets
        sp.ctfstats = new CTF_Stats();

        // --- Third session ---
        sp.ctfstats.score = 3;
        sp.ctfstats.deaths = 1;

        // Effective total = all three sessions
        assertEquals(20, sp.statsOffset.score + sp.ctfstats.score);
        assertEquals(6, sp.statsOffset.deaths + sp.ctfstats.deaths);
        assertEquals(2, sp.statsOffset.assists + sp.ctfstats.assists);
        assertEquals(2, sp.statsOffset.caps + sp.ctfstats.caps);
    }

    @Test
    void noDisconnect_statsOffsetRemainsZero() {
        ServerPlayer sp = new ServerPlayer();
        sp.auth = "charlie";

        // Player plays the whole match without disconnecting
        sp.ctfstats.score = 25;
        sp.ctfstats.deaths = 10;
        sp.ctfstats.assists = 8;
        sp.ctfstats.caps = 3;

        // Effective total = offset(0) + current
        assertEquals(25, sp.statsOffset.score + sp.ctfstats.score);
        assertEquals(10, sp.statsOffset.deaths + sp.ctfstats.deaths);
        assertEquals(8, sp.statsOffset.assists + sp.ctfstats.assists);
        assertEquals(3, sp.statsOffset.caps + sp.ctfstats.caps);

        // statsOffset should still be 0
        assertEquals(0, sp.statsOffset.score);
        assertEquals(0, sp.statsOffset.deaths);
    }

    @Test
    void halftimeReset_clearsStatsOffset() {
        ServerPlayer sp = new ServerPlayer();
        sp.auth = "delta";

        // First half: play and disconnect
        sp.ctfstats.score = 10;
        sp.ctfstats.deaths = 5;
        sp.statsOffset.add(sp.ctfstats);
        assertEquals(10, sp.statsOffset.score);

        // Halftime — reset offset (simulates what handleScoreTransition does)
        sp.statsOffset = new CTF_Stats();
        assertEquals(0, sp.statsOffset.score);
        assertEquals(0, sp.statsOffset.deaths);

        // Second half: server resets stats to 0
        sp.ctfstats = new CTF_Stats();
        sp.ctfstats.score = 8;
        sp.ctfstats.deaths = 3;

        // Second half total should only reflect second half
        assertEquals(8, sp.statsOffset.score + sp.ctfstats.score);
        assertEquals(3, sp.statsOffset.deaths + sp.ctfstats.deaths);
    }

    @Test
    void halftimeReset_thenDisconnectInSecondHalf_worksCorrectly() {
        ServerPlayer sp = new ServerPlayer();
        sp.auth = "echo";

        // First half: accumulate some stats and disconnect
        sp.ctfstats.score = 15;
        sp.ctfstats.deaths = 7;
        sp.ctfstats.caps = 2;
        sp.statsOffset.add(sp.ctfstats);

        // Halftime — reset offset
        sp.statsOffset = new CTF_Stats();

        // Second half starts, server resets scores
        sp.ctfstats = new CTF_Stats();
        sp.ctfstats.score = 6;
        sp.ctfstats.deaths = 2;
        sp.ctfstats.caps = 1;

        // Disconnect in second half
        sp.statsOffset.add(sp.ctfstats);
        assertEquals(6, sp.statsOffset.score);
        assertEquals(2, sp.statsOffset.deaths);

        // Reconnect — server resets again
        sp.ctfstats = new CTF_Stats();
        sp.ctfstats.score = 4;
        sp.ctfstats.deaths = 1;

        // Second half total
        assertEquals(10, sp.statsOffset.score + sp.ctfstats.score);
        assertEquals(3, sp.statsOffset.deaths + sp.ctfstats.deaths);
        assertEquals(1, sp.statsOffset.caps + sp.ctfstats.caps);
    }

    @Test
    void disconnectReconnect_allCtfStatsFieldsPreserved() {
        ServerPlayer sp = new ServerPlayer();
        sp.auth = "foxtrot";

        // Set all CTF stat fields
        sp.ctfstats.score = 20;
        sp.ctfstats.deaths = 8;
        sp.ctfstats.assists = 6;
        sp.ctfstats.caps = 3;
        sp.ctfstats.returns = 5;
        sp.ctfstats.fc_kills = 2;
        sp.ctfstats.stop_caps = 1;
        sp.ctfstats.protect_flag = 4;

        // Disconnect
        sp.statsOffset.add(sp.ctfstats);

        // Reconnect — all reset to 0
        sp.ctfstats = new CTF_Stats();

        // Continue playing
        sp.ctfstats.score = 5;
        sp.ctfstats.deaths = 2;
        sp.ctfstats.assists = 1;
        sp.ctfstats.caps = 1;
        sp.ctfstats.returns = 2;
        sp.ctfstats.fc_kills = 1;
        sp.ctfstats.stop_caps = 0;
        sp.ctfstats.protect_flag = 1;

        // Verify all fields are correctly summed
        assertEquals(25, sp.statsOffset.score + sp.ctfstats.score);
        assertEquals(10, sp.statsOffset.deaths + sp.ctfstats.deaths);
        assertEquals(7, sp.statsOffset.assists + sp.ctfstats.assists);
        assertEquals(4, sp.statsOffset.caps + sp.ctfstats.caps);
        assertEquals(7, sp.statsOffset.returns + sp.ctfstats.returns);
        assertEquals(3, sp.statsOffset.fc_kills + sp.ctfstats.fc_kills);
        assertEquals(1, sp.statsOffset.stop_caps + sp.ctfstats.stop_caps);
        assertEquals(5, sp.statsOffset.protect_flag + sp.ctfstats.protect_flag);
    }

    // ========== CTF_Stats.hasTrackedStats() ==========

    @Test
    void hasTrackedStats_allZeros_returnsFalse() {
        CTF_Stats stats = new CTF_Stats();
        assertFalse(stats.hasTrackedStats());
    }

    @Test
    void hasTrackedStats_withScore_returnsTrue() {
        CTF_Stats stats = new CTF_Stats();
        stats.score = 1;
        assertTrue(stats.hasTrackedStats());
    }

    @Test
    void hasTrackedStats_withDeaths_returnsTrue() {
        CTF_Stats stats = new CTF_Stats();
        stats.deaths = 1;
        assertTrue(stats.hasTrackedStats());
    }

    @Test
    void hasTrackedStats_withAssists_returnsTrue() {
        CTF_Stats stats = new CTF_Stats();
        stats.assists = 1;
        assertTrue(stats.hasTrackedStats());
    }

    @Test
    void hasTrackedStats_withCaps_returnsTrue() {
        CTF_Stats stats = new CTF_Stats();
        stats.caps = 1;
        assertTrue(stats.hasTrackedStats());
    }

    // ========== CTF_Stats.totalsEqual() ==========

    @Test
    void totalsEqual_identicalStats_returnsTrue() {
        CTF_Stats a = new CTF_Stats();
        a.score = 10;
        a.deaths = 5;
        a.assists = 3;
        a.caps = 2;
        a.returns = 1;
        a.fc_kills = 1;
        a.stop_caps = 0;
        a.protect_flag = 1;

        CTF_Stats b = new CTF_Stats();
        b.score = 10;
        b.deaths = 5;
        b.assists = 3;
        b.caps = 2;
        b.returns = 1;
        b.fc_kills = 1;
        b.stop_caps = 0;
        b.protect_flag = 1;

        assertTrue(a.totalsEqual(b));
        assertTrue(b.totalsEqual(a));
    }

    @Test
    void totalsEqual_differentScore_returnsFalse() {
        CTF_Stats a = new CTF_Stats();
        a.score = 10;

        CTF_Stats b = new CTF_Stats();
        b.score = 11;

        assertFalse(a.totalsEqual(b));
    }

    @Test
    void totalsEqual_null_returnsFalse() {
        CTF_Stats a = new CTF_Stats();
        a.score = 10;

        assertFalse(a.totalsEqual(null));
    }

    // ========== CTF_Stats.atLeastAs() ==========

    @Test
    void atLeastAs_equalStats_returnsTrue() {
        CTF_Stats server = new CTF_Stats();
        server.score = 38;
        server.deaths = 25;
        server.assists = 7;

        CTF_Stats offset = new CTF_Stats();
        offset.score = 38;
        offset.deaths = 25;
        offset.assists = 7;

        assertTrue(server.atLeastAs(offset));
    }

    @Test
    void atLeastAs_serverHigher_returnsTrue() {
        CTF_Stats server = new CTF_Stats();
        server.score = 40;
        server.deaths = 26;
        server.assists = 8;

        CTF_Stats offset = new CTF_Stats();
        offset.score = 38;
        offset.deaths = 25;
        offset.assists = 7;

        assertTrue(server.atLeastAs(offset));
    }

    @Test
    void atLeastAs_serverLower_returnsFalse() {
        CTF_Stats server = new CTF_Stats();
        server.score = 0;
        server.deaths = 0;
        server.assists = 0;

        CTF_Stats offset = new CTF_Stats();
        offset.score = 38;
        offset.deaths = 25;
        offset.assists = 7;

        assertFalse(server.atLeastAs(offset));
    }

    @Test
    void atLeastAs_partiallyLower_returnsFalse() {
        CTF_Stats server = new CTF_Stats();
        server.score = 40;  // higher
        server.deaths = 20; // lower
        server.assists = 8;

        CTF_Stats offset = new CTF_Stats();
        offset.score = 38;
        offset.deaths = 25;
        offset.assists = 7;

        assertFalse(server.atLeastAs(offset));
    }

    @Test
    void atLeastAs_null_returnsTrue() {
        CTF_Stats server = new CTF_Stats();
        server.score = 10;

        assertTrue(server.atLeastAs(null));
    }

    // ========== ServerPlayer.preserveStatsIfReset() ==========

    @Test
    void preserveStatsIfReset_serverStatsReset_addsToOffset() {
        ServerPlayer sp = new ServerPlayer();
        // Player has stats from playing
        sp.ctfstats.score = 38;
        sp.ctfstats.deaths = 25;
        sp.ctfstats.assists = 7;

        // Server sends reset stats (player reconnected)
        CTF_Stats serverStats = new CTF_Stats();
        serverStats.score = 0;
        serverStats.deaths = 0;
        serverStats.assists = 0;

        boolean wasReset = sp.preserveStatsIfReset(serverStats);

        assertTrue(wasReset);
        // Current stats should be preserved to offset
        assertEquals(38, sp.statsOffset.score);
        assertEquals(25, sp.statsOffset.deaths);
        assertEquals(7, sp.statsOffset.assists);
    }

    @Test
    void preserveStatsIfReset_serverStatsEqual_noChange() {
        ServerPlayer sp = new ServerPlayer();
        // Player has stats from playing
        sp.ctfstats.score = 38;
        sp.ctfstats.deaths = 25;
        sp.ctfstats.assists = 7;

        // Server sends same stats (no reset, normal update)
        CTF_Stats serverStats = new CTF_Stats();
        serverStats.score = 38;
        serverStats.deaths = 25;
        serverStats.assists = 7;

        boolean wasReset = sp.preserveStatsIfReset(serverStats);

        assertFalse(wasReset);
        // Offset should remain zero
        assertEquals(0, sp.statsOffset.score);
    }

    @Test
    void preserveStatsIfReset_serverStatsHigher_noChange() {
        ServerPlayer sp = new ServerPlayer();
        // Player has stats from playing
        sp.ctfstats.score = 38;
        sp.ctfstats.deaths = 25;
        sp.ctfstats.assists = 7;

        // Server sends higher stats (player got more kills)
        CTF_Stats serverStats = new CTF_Stats();
        serverStats.score = 40;
        serverStats.deaths = 26;
        serverStats.assists = 8;

        boolean wasReset = sp.preserveStatsIfReset(serverStats);

        assertFalse(wasReset);
        // Offset should remain zero
        assertEquals(0, sp.statsOffset.score);
    }

    @Test
    void preserveStatsIfReset_noCurrentStats_noChange() {
        ServerPlayer sp = new ServerPlayer();
        // Player has no stats yet (just connected)

        CTF_Stats serverStats = new CTF_Stats();
        serverStats.score = 10;
        serverStats.deaths = 5;

        boolean wasReset = sp.preserveStatsIfReset(serverStats);

        assertFalse(wasReset);
        assertEquals(0, sp.statsOffset.score);
    }

    @Test
    void preserveStatsIfReset_accumulates_onMultipleReconnects() {
        ServerPlayer sp = new ServerPlayer();

        // First session: 10 kills
        sp.ctfstats.score = 10;
        sp.ctfstats.deaths = 3;

        // First reconnect - stats reset
        CTF_Stats reset1 = new CTF_Stats();
        assertTrue(sp.preserveStatsIfReset(reset1));
        sp.ctfstats = reset1; // simulate copy

        assertEquals(10, sp.statsOffset.score);
        assertEquals(3, sp.statsOffset.deaths);

        // Second session: 7 more kills
        sp.ctfstats.score = 7;
        sp.ctfstats.deaths = 2;

        // Second reconnect - stats reset again
        CTF_Stats reset2 = new CTF_Stats();
        assertTrue(sp.preserveStatsIfReset(reset2));
        sp.ctfstats = reset2;

        // Offset should now have both sessions
        assertEquals(17, sp.statsOffset.score);
        assertEquals(5, sp.statsOffset.deaths);
    }

    // ========== Bug documentation: stats doubled in non-swaproles games (NOW FIXED) ==========

    /**
     * Documents the bug where stats were doubled in non-swaproles games.
     *
     * The issue WAS: When a player disconnects/reconnects between prevRPP capture and handleScoreTransition:
     * 1. prevRPP is captured with player's pre-reconnect stats (score=10)
     * 2. Player reconnects - updatePlayers preserves stats to offset (offset=10), ctfstats reset to 0
     * 3. handleScoreTransition calls saveStats(prevRPP)
     * 4. Old code used prevRPP.ctfstats (10) + offset (10) = 20 (DOUBLED!)
     *
     * The fix: saveStats now checks if player has statsOffset values (indicating reconnect).
     * If so, it uses the tracked player's current ctfstats (post-reconnect) instead of
     * prevRPP.ctfstats (which might have stale pre-reconnect data).
     *
     * This test simulates the OLD buggy flow to document what was happening.
     */
    @Test
    void bug_statsDoubled_whenSaveStatsUsesStaleRppAfterReconnect_DOCUMENTED() {
        // Setup: tracked player with accumulated stats
        ServerPlayer tracked = new ServerPlayer();
        tracked.auth = "bugPlayer";
        tracked.ctfstats.score = 10;
        tracked.ctfstats.deaths = 5;
        tracked.ctfstats.assists = 3;

        // prevRPP: server data from PREVIOUS poll (before reconnect), has old stats
        ServerPlayer prevRppPlayer = new ServerPlayer();
        prevRppPlayer.auth = "bugPlayer";
        prevRppPlayer.ctfstats = new CTF_Stats();
        prevRppPlayer.ctfstats.score = 10;
        prevRppPlayer.ctfstats.deaths = 5;
        prevRppPlayer.ctfstats.assists = 3;

        // currentRpp: server data from CURRENT poll (after reconnect), stats reset to 0
        ServerPlayer currentRppPlayer = new ServerPlayer();
        currentRppPlayer.auth = "bugPlayer";
        currentRppPlayer.ctfstats = new CTF_Stats();
        currentRppPlayer.ctfstats.score = 0;
        currentRppPlayer.ctfstats.deaths = 0;
        currentRppPlayer.ctfstats.assists = 0;

        // === Step 1: updatePlayers processes currentRpp ===
        // Detects reconnect (stats reset), preserves to offset
        boolean resetDetected = tracked.preserveStatsIfReset(currentRppPlayer.ctfstats);
        assertTrue(resetDetected, "Should detect stats were reset");
        assertEquals(10, tracked.statsOffset.score, "Stats should be preserved to offset");

        // updatePlayers then calls copy() with current (reset) data
        tracked.copy(currentRppPlayer);
        assertEquals(0, tracked.ctfstats.score, "ctfstats should be 0 after copy from current");

        // At this point: statsOffset=10, ctfstats=0, correct total would be 10

        // === OLD BUGGY BEHAVIOR: using prevRPP.ctfstats directly ===
        // The old code would add: offset (10) + prevRPP.ctfstats (10) = 20 (DOUBLED!)
        int buggyScore = tracked.statsOffset.score + prevRppPlayer.ctfstats.score;
        assertEquals(20, buggyScore, "OLD BUG: Score doubled when using stale prevRPP.ctfstats");

        // === NEW FIXED BEHAVIOR: if offset has values, use tracked.ctfstats ===
        // The fix checks: if statsOffset.hasTrackedStats(), use player.ctfstats instead of rpp.ctfstats
        CTF_Stats ctfstatsToUse = tracked.statsOffset.hasTrackedStats() ? tracked.ctfstats : prevRppPlayer.ctfstats;
        int fixedScore = tracked.statsOffset.score + ctfstatsToUse.score;
        assertEquals(10, fixedScore, "FIXED: Score correct when using tracked.ctfstats for reconnected player");
    }

    /**
     * Shows that non-reconnected players still use rpp.ctfstats correctly.
     * This ensures the fix doesn't break normal (non-reconnect) stat saving.
     */
    @Test
    void fixed_nonReconnectedPlayer_usesRppStats() {
        // Setup: tracked player with NO reconnect (statsOffset is empty)
        ServerPlayer tracked = new ServerPlayer();
        tracked.auth = "normalPlayer";
        tracked.ctfstats.score = 15;  // Current tracked stats (might be warmup data)
        tracked.ctfstats.deaths = 7;
        tracked.ctfstats.assists = 4;
        // statsOffset is 0 (no reconnects)

        // prevRPP: has the final LIVE stats we want to save
        ServerPlayer prevRppPlayer = new ServerPlayer();
        prevRppPlayer.auth = "normalPlayer";
        prevRppPlayer.ctfstats = new CTF_Stats();
        prevRppPlayer.ctfstats.score = 20;  // Final LIVE score
        prevRppPlayer.ctfstats.deaths = 10;
        prevRppPlayer.ctfstats.assists = 5;

        // Since statsOffset is empty, we should use prevRPP.ctfstats (final LIVE stats)
        assertFalse(tracked.statsOffset.hasTrackedStats(), "No reconnect, offset should be empty");

        CTF_Stats ctfstatsToUse = tracked.statsOffset.hasTrackedStats() ? tracked.ctfstats : prevRppPlayer.ctfstats;
        int finalScore = tracked.statsOffset.score + ctfstatsToUse.score;

        // Should use prevRPP stats (20), not tracked stats (15)
        assertEquals(20, finalScore, "Non-reconnected player should use prevRPP.ctfstats");
    }

    /**
     * Shows correct behavior for a reconnected player who continued playing after reconnect.
     */
    @Test
    void fixed_reconnectedPlayer_accumulatesPostReconnectStats() {
        // Setup: player who had 10 kills, reconnected, then got 5 more
        ServerPlayer tracked = new ServerPlayer();
        tracked.auth = "activePlayer";

        // Pre-reconnect stats that were preserved
        tracked.statsOffset.score = 10;
        tracked.statsOffset.deaths = 5;

        // Post-reconnect stats (player continued playing)
        tracked.ctfstats.score = 5;
        tracked.ctfstats.deaths = 2;

        assertTrue(tracked.statsOffset.hasTrackedStats(), "Player reconnected, should have offset");

        // Even if prevRPP has old stats, we use tracked.ctfstats for reconnected players
        ServerPlayer prevRppPlayer = new ServerPlayer();
        prevRppPlayer.ctfstats = new CTF_Stats();
        prevRppPlayer.ctfstats.score = 10;  // Stale pre-reconnect stats

        CTF_Stats ctfstatsToUse = tracked.statsOffset.hasTrackedStats() ? tracked.ctfstats : prevRppPlayer.ctfstats;
        int finalScore = tracked.statsOffset.score + ctfstatsToUse.score;
        int finalDeaths = tracked.statsOffset.deaths + ctfstatsToUse.deaths;

        // offset (10) + current ctfstats (5) = 15 total
        assertEquals(15, finalScore, "Total should be pre-reconnect (10) + post-reconnect (5)");
        assertEquals(7, finalDeaths, "Total deaths should be pre-reconnect (5) + post-reconnect (2)");
    }

    // ========== Mid-play stat penalties must not look like a reset (match #39664) ==========

    @Test
    void teamKill_doesNotInflateOffset() {
        // TK drops score by 1 but leaves deaths/assists alone.
        ServerPlayer sp = new ServerPlayer();
        sp.ctfstats.score = 10;
        sp.ctfstats.deaths = 8;
        sp.ctfstats.assists = 3;

        CTF_Stats afterTK = new CTF_Stats();
        afterTK.score = 9;
        afterTK.deaths = 8;
        afterTK.assists = 3;

        assertFalse(sp.preserveStatsIfReset(afterTK), "TK must not be treated as a reset");
        assertEquals(0, sp.statsOffset.score);
        assertEquals(0, sp.statsOffset.deaths);
        assertEquals(0, sp.statsOffset.assists);
    }

    @Test
    void suicide_doesNotInflateOffset() {
        // Suicide bumps deaths by 1 and drops score by 1.
        ServerPlayer sp = new ServerPlayer();
        sp.ctfstats.score = 10;
        sp.ctfstats.deaths = 8;
        sp.ctfstats.assists = 3;

        CTF_Stats afterSuicide = new CTF_Stats();
        afterSuicide.score = 9;
        afterSuicide.deaths = 9;
        afterSuicide.assists = 3;

        assertFalse(sp.preserveStatsIfReset(afterSuicide), "Suicide must not be treated as a reset");
        assertEquals(0, sp.statsOffset.score);
    }

    @Test
    void multipleTKs_overFullGame_doNotInflate() {
        // Match #39664 endriuurt shape: 2 TKs during play. Old !atLeastAs
        // check dumped current ctfstats into offset on each TK, inflating the
        // final total by the sum of per-TK ctfstats snapshots.
        ServerPlayer sp = new ServerPlayer();

        sp.ctfstats.score = 5;
        sp.ctfstats.deaths = 3;
        CTF_Stats tk1 = new CTF_Stats();
        tk1.score = 4;
        tk1.deaths = 3;
        assertFalse(sp.preserveStatsIfReset(tk1));
        sp.ctfstats = tk1;

        sp.ctfstats.score = 15;
        sp.ctfstats.deaths = 18;
        CTF_Stats tk2 = new CTF_Stats();
        tk2.score = 14;
        tk2.deaths = 18;
        assertFalse(sp.preserveStatsIfReset(tk2));
        sp.ctfstats = tk2;

        sp.ctfstats.score = 20;
        sp.ctfstats.deaths = 27;

        assertEquals(0, sp.statsOffset.score);
        assertEquals(0, sp.statsOffset.deaths);
        assertEquals(20, sp.statsOffset.score + sp.ctfstats.score);
        assertEquals(27, sp.statsOffset.deaths + sp.ctfstats.deaths);
    }

    @Test
    void fastReconnect_detectedViaDeathsDecrease() {
        // A /reconnect that completes inside one 1s poll never shows the
        // Disconnected state — we rely on deaths dropping to detect it.
        ServerPlayer sp = new ServerPlayer();
        sp.ctfstats.score = 15;
        sp.ctfstats.deaths = 8;
        sp.ctfstats.assists = 4;

        CTF_Stats afterFastReconnect = new CTF_Stats();

        assertTrue(sp.preserveStatsIfReset(afterFastReconnect), "Fast reconnect must still be detected");
        assertEquals(15, sp.statsOffset.score);
        assertEquals(8, sp.statsOffset.deaths);
        assertEquals(4, sp.statsOffset.assists);
    }
}
