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
}
