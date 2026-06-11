package de.gost0r.pickupbot.pickup.server;

import de.gost0r.pickupbot.pickup.server.ServerPlayer.ServerPlayerState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Replays match #39667 tick-by-tick against a simulator that mirrors
 * ServerMonitor.updatePlayers + saveStats, trying to reproduce the
 * Discord display of jsn 645/711/364 (ground truth 31/34/12) when
 * jsn's client reconnected with a new slot (PlayerNo 2 -> PlayerNo 10).
 *
 * Data source: FTW ScoreReport for match #39667, player_stats kill log.
 */
class Match39667ReplayTest {

    // ---- Ground-truth per-round kill counts (from JSON Rounds.Kills) ----
    // jsn played slot 2 for rounds 1..27 and slot 10 for rounds 27..39.
    private static final int[] JSN_SLOT2_KILLS = {
            0, 1, 1, 0, 2, 1, 0, 2, 1, 1, 1, 0, 2, 1, 3, 0, 2,
            0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
    };
    private static final boolean[] JSN_SLOT2_DIED = {
            true, false, true, true, true, true, true, true, true, true, true, true, true, true,
            false, true, false, false, false, true, true, true, true, true, true, true, true,
            false, false, false, false, false, false, false, false, false, false, false, false
    };
    // slot 10: only 13 rounds of data (rounds 27..39).
    private static final int[] JSN_SLOT10_KILLS = { 0, 0, 0, 4, 0, 0, 0, 1, 2, 1, 2, 1, 0 };
    private static final boolean[] JSN_SLOT10_DIED = {
            true, true, true, true, true, true, true, true, false, true, true, true, true
    };

    private static final int JSN_SLOT2_FINAL_ASSISTS = 11;
    private static final int JSN_SLOT10_FINAL_ASSISTS = 1;

    private static final int JSN_EXPECTED_KILLS = 31;  // 20 + 11
    private static final int JSN_EXPECTED_DEATHS = 34; // 22 + 12
    private static final int JSN_EXPECTED_ASSISTS = 12; // 11 + 1

    /** slot 2 first connects at round 1 and stops generating events after round 27. */
    private static int jsnSlot2Kills(int roundIdx) {
        int sum = 0;
        for (int i = 0; i <= roundIdx && i < JSN_SLOT2_KILLS.length; i++) sum += JSN_SLOT2_KILLS[i];
        return sum;
    }
    private static int jsnSlot2Deaths(int roundIdx) {
        int sum = 0;
        for (int i = 0; i <= roundIdx && i < JSN_SLOT2_DIED.length; i++) if (JSN_SLOT2_DIED[i]) sum++;
        return sum;
    }
    /** Linear assist growth: 0 by round 0, 11 by round 26 (slot 2 finishes round 27). */
    private static int jsnSlot2Assists(int roundIdx) {
        if (roundIdx < 0) return 0;
        if (roundIdx >= 26) return JSN_SLOT2_FINAL_ASSISTS;
        return (JSN_SLOT2_FINAL_ASSISTS * (roundIdx + 1)) / 27;
    }

    /** slot 10 first appears at round 27 (worldIdx 26). localIdx = worldIdx - 26. */
    private static int jsnSlot10Kills(int worldIdx) {
        int local = worldIdx - 26;
        if (local < 0) return 0;
        int sum = 0;
        for (int i = 0; i <= local && i < JSN_SLOT10_KILLS.length; i++) sum += JSN_SLOT10_KILLS[i];
        return sum;
    }
    private static int jsnSlot10Deaths(int worldIdx) {
        int local = worldIdx - 26;
        if (local < 0) return 0;
        int sum = 0;
        for (int i = 0; i <= local && i < JSN_SLOT10_DIED.length; i++) if (JSN_SLOT10_DIED[i]) sum++;
        return sum;
    }
    private static int jsnSlot10Assists(int worldIdx) {
        int local = worldIdx - 26;
        if (local < 0) return 0;
        if (local >= 12) return JSN_SLOT10_FINAL_ASSISTS;
        return (JSN_SLOT10_FINAL_ASSISTS * (local + 1)) / 13;
    }

    // ------------- Simulator that mirrors ServerMonitor's merge logic -------------

    /**
     * Faithful mirror of ServerMonitor.updatePlayers + saveStats. Kept in the test
     * because ServerMonitor's constructor pulls in Server/Match/PermissionService
     * wiring that isn't needed for stats math.
     */
    static final class Simulator {
        final List<ServerPlayer> players = new ArrayList<>();
        final Map<String, CTF_Stats> savedStats = new HashMap<>();

        void tick(List<ServerPlayer> rppPlayers) {
            List<ServerPlayer> deduped = dedupeByAuth(rppPlayers);
            updatePlayers(deduped);
            saveStats(deduped);
        }

        /** Mirrors ServerMonitor.dedupeByAuth. */
        private List<ServerPlayer> dedupeByAuth(List<ServerPlayer> rppPlayers) {
            Map<String, ServerPlayer> seen = new LinkedHashMap<>();
            List<ServerPlayer> out = new ArrayList<>();
            for (ServerPlayer sp : rppPlayers) {
                if (sp.auth == null || sp.auth.isEmpty() || "---".equals(sp.auth)) {
                    out.add(sp);
                    continue;
                }
                ServerPlayer cur = seen.get(sp.auth);
                if (cur == null || preferCandidate(cur, sp)) seen.put(sp.auth, sp);
            }
            out.addAll(seen.values());
            return out;
        }

        private boolean preferCandidate(ServerPlayer current, ServerPlayer candidate) {
            for (ServerPlayer p : players) {
                if (!candidate.auth.equals(p.auth)) continue;
                if (p.statsOffset.hasTrackedStats()) {
                    if (p.id.equals(candidate.id)) return true;
                    if (p.id.equals(current.id)) return false;
                }
                break;
            }
            return candidate.ctfstats.deaths < current.ctfstats.deaths;
        }

        private void updatePlayers(List<ServerPlayer> rppPlayers) {
            List<ServerPlayer> oldPlayers = new ArrayList<>(players);
            List<ServerPlayer> newPlayers = new ArrayList<>();
            for (ServerPlayer rppPlayer : rppPlayers) {
                ServerPlayer found = null;
                for (ServerPlayer tracked : players) {
                    if (rppPlayer.equals(tracked)) {
                        boolean wasDisconnected = tracked.state == ServerPlayerState.Disconnected;
                        tracked.preserveStatsIfReset(rppPlayer.ctfstats);
                        tracked.copy(rppPlayer);
                        found = tracked;
                        if (wasDisconnected) {
                            found.state = ServerPlayerState.Reconnected;
                            found.timeDisconnect = -1L;
                        }
                        break;
                    }
                }
                if (found != null) {
                    oldPlayers.remove(found);
                } else {
                    newPlayers.add(rppPlayer);
                }
            }
            for (ServerPlayer p : oldPlayers) {
                if (p.state != ServerPlayerState.Disconnected) {
                    p.state = ServerPlayerState.Disconnected;
                    p.timeDisconnect = System.currentTimeMillis();
                }
            }
            players.addAll(newPlayers);
        }

        private void saveStats(List<ServerPlayer> rppPlayers) {
            for (ServerPlayer player : players) {
                ServerPlayer rppPlayer = null;
                for (ServerPlayer sp : rppPlayers) {
                    if (sp.equals(player)) {
                        rppPlayer = sp;
                        break;
                    }
                }
                if (rppPlayer == null) continue;

                CTF_Stats use = player.statsOffset.hasTrackedStats()
                        ? player.ctfstats
                        : rppPlayer.ctfstats;

                CTF_Stats saved = savedStats.computeIfAbsent(player.auth, k -> new CTF_Stats());
                saved.score = player.statsOffset.score + use.score;
                saved.deaths = player.statsOffset.deaths + use.deaths;
                saved.assists = player.statsOffset.assists + use.assists;
            }
        }
    }

    // ------------- RPP builders -------------

    private static ServerPlayer makeRppPlayer(String id, String auth, String team,
                                              int score, int deaths, int assists) {
        ServerPlayer sp = new ServerPlayer();
        sp.id = id;
        sp.name = auth;
        sp.team = team;
        sp.auth = auth;
        sp.ping = "50";
        sp.ip = "1.2.3.4";
        sp.state = ServerPlayerState.Connected;
        sp.ctfstats.score = score;
        sp.ctfstats.deaths = deaths;
        sp.ctfstats.assists = assists;
        return sp;
    }

    // ------------- Tests -------------

    /** Baseline: slot 2 exits cleanly before slot 10 appears. Expect correct totals. */
    @Test
    void cleanSlotSwap_jsnHasCorrectTotals() {
        Simulator sim = new Simulator();

        // Rounds 1..27: slot 2 only.
        for (int r = 0; r < 27; r++) {
            List<ServerPlayer> rpp = new ArrayList<>();
            rpp.add(makeRppPlayer("2", "jsn", "blue",
                    jsnSlot2Kills(r), jsnSlot2Deaths(r), jsnSlot2Assists(r)));
            sim.tick(rpp);
        }

        // One tick with no jsn at all (slot 2 gone, slot 10 not yet).
        sim.tick(new ArrayList<>());

        // Rounds 27..39: slot 10 only.
        for (int r = 26; r < 39; r++) {
            List<ServerPlayer> rpp = new ArrayList<>();
            rpp.add(makeRppPlayer("10", "jsn", "blue",
                    jsnSlot10Kills(r), jsnSlot10Deaths(r), jsnSlot10Assists(r)));
            sim.tick(rpp);
        }

        CTF_Stats jsn = sim.savedStats.get("jsn");
        assertNotNull(jsn);
        assertEquals(JSN_EXPECTED_KILLS, jsn.score, "clean swap: kills should match ground truth");
        assertEquals(JSN_EXPECTED_DEATHS, jsn.deaths, "clean swap: deaths should match ground truth");
        assertEquals(JSN_EXPECTED_ASSISTS, jsn.assists, "clean swap: assists should match ground truth");
    }

    /**
     * Fast /reconnect: slot 2 leaves and slot 10 appears between two consecutive
     * polls, with no intermediate poll seeing an empty player list.
     */
    @Test
    void fastReconnectSlotSwap_jsnHasCorrectTotals() {
        Simulator sim = new Simulator();

        for (int r = 0; r < 27; r++) {
            List<ServerPlayer> rpp = new ArrayList<>();
            rpp.add(makeRppPlayer("2", "jsn", "blue",
                    jsnSlot2Kills(r), jsnSlot2Deaths(r), jsnSlot2Assists(r)));
            sim.tick(rpp);
        }

        for (int r = 26; r < 39; r++) {
            List<ServerPlayer> rpp = new ArrayList<>();
            rpp.add(makeRppPlayer("10", "jsn", "blue",
                    jsnSlot10Kills(r), jsnSlot10Deaths(r), jsnSlot10Assists(r)));
            sim.tick(rpp);
        }

        CTF_Stats jsn = sim.savedStats.get("jsn");
        assertNotNull(jsn);
        assertEquals(JSN_EXPECTED_KILLS, jsn.score, "fast reconnect: kills should match");
        assertEquals(JSN_EXPECTED_DEATHS, jsn.deaths, "fast reconnect: deaths should match");
        assertEquals(JSN_EXPECTED_ASSISTS, jsn.assists, "fast reconnect: assists should match");
    }

    /**
     * Dual-slot scenario: the server's rcon-players response shows BOTH slot 2
     * (zombie/ghost) and slot 10 (new, active) for the jsn auth on the same
     * tick. The FTW kill log for match #39667 confirms this — both PlayerNo 2
     * and PlayerNo 10 register kill events in round 27.
     *
     * Since ServerPlayer.equals is keyed on auth, both rpp entries match the
     * same tracked jsn in updatePlayers. On each tick both entries run through
     * preserveStatsIfReset, and the 0/0/0 entry for slot 10 triggers a fake
     * "reset" detection that dumps slot 2's stats into the offset every tick.
     *
     * Result: offset grows by slot-2-cumulative per tick, producing the
     * massive inflation the user saw (645/711/364 vs. ground truth 31/34/12).
     */
    @Test
    void dualSlotOverlap_jsnHasCorrectTotals() {
        Simulator sim = new Simulator();

        for (int r = 0; r < 27; r++) {
            List<ServerPlayer> rpp = new ArrayList<>();
            rpp.add(makeRppPlayer("2", "jsn", "blue",
                    jsnSlot2Kills(r), jsnSlot2Deaths(r), jsnSlot2Assists(r)));
            sim.tick(rpp);
        }
        for (int r = 26; r < 39; r++) {
            List<ServerPlayer> rpp = new ArrayList<>();
            rpp.add(makeRppPlayer("2", "jsn", "blue", 20, 22, 11));
            rpp.add(makeRppPlayer("10", "jsn", "blue",
                    jsnSlot10Kills(r), jsnSlot10Deaths(r), jsnSlot10Assists(r)));
            sim.tick(rpp);
        }

        CTF_Stats jsn = sim.savedStats.get("jsn");
        assertNotNull(jsn);
        assertEquals(JSN_EXPECTED_KILLS, jsn.score);
        assertEquals(JSN_EXPECTED_DEATHS, jsn.deaths);
        assertEquals(JSN_EXPECTED_ASSISTS, jsn.assists);
    }

    /**
     * Realistic tick rate (~60 polls per round) with dual-slot for the first
     * ~40s of round 27 — roughly how long slot 2 lingers before the server's
     * g_inactivity timeout (40s in TS.cfg) kicks it. Production saw 645/711/364
     * without the dedupe; with it the totals collapse to the ground truth.
     */
    @Test
    void dualSlotOverlap_realisticTickRate_producesGroundTruth() {
        final int TICKS_PER_ROUND = 60;
        final int DUAL_SLOT_TICKS = 40;
        Simulator sim = new Simulator();

        for (int r = 0; r < 27; r++) {
            for (int t = 0; t < TICKS_PER_ROUND; t++) {
                List<ServerPlayer> rpp = new ArrayList<>();
                rpp.add(makeRppPlayer("2", "jsn", "blue",
                        jsnSlot2Kills(r), jsnSlot2Deaths(r), jsnSlot2Assists(r)));
                sim.tick(rpp);
            }
        }
        for (int t = 0; t < DUAL_SLOT_TICKS; t++) {
            List<ServerPlayer> rpp = new ArrayList<>();
            rpp.add(makeRppPlayer("2", "jsn", "blue", 20, 22, 11));
            rpp.add(makeRppPlayer("10", "jsn", "blue", 0, 0, 0));
            sim.tick(rpp);
        }
        for (int r = 27; r < 39; r++) {
            for (int t = 0; t < TICKS_PER_ROUND; t++) {
                List<ServerPlayer> rpp = new ArrayList<>();
                rpp.add(makeRppPlayer("10", "jsn", "blue",
                        jsnSlot10Kills(r), jsnSlot10Deaths(r), jsnSlot10Assists(r)));
                sim.tick(rpp);
            }
        }

        CTF_Stats jsn = sim.savedStats.get("jsn");
        assertNotNull(jsn);
        assertEquals(JSN_EXPECTED_KILLS, jsn.score);
        assertEquals(JSN_EXPECTED_DEATHS, jsn.deaths);
        assertEquals(JSN_EXPECTED_ASSISTS, jsn.assists);
    }

    /**
     * Direct unit test of the dedupe logic: rpp has both slot 2 (stale) and
     * slot 10 (fresh) for jsn. After dedupe, only slot 10 should remain since
     * that's the id we aren't currently tracking.
     */
    @Test
    void dedupeByAuth_keepsNewerSlotWhenBothPresent() {
        Simulator sim = new Simulator();
        List<ServerPlayer> seed = new ArrayList<>();
        seed.add(makeRppPlayer("2", "jsn", "blue", 20, 22, 11));
        sim.tick(seed);

        List<ServerPlayer> dualRpp = new ArrayList<>();
        dualRpp.add(makeRppPlayer("2", "jsn", "blue", 20, 22, 11));
        dualRpp.add(makeRppPlayer("10", "jsn", "blue", 0, 0, 0));

        List<ServerPlayer> deduped = sim.dedupeByAuth(dualRpp);
        assertEquals(1, deduped.size());
        assertEquals("10", deduped.get(0).id, "should keep the slot we aren't tracking yet");
    }

    /**
     * Ghost times out after 1 round (low death total), active client plays
     * 2 more rounds and racks up more deaths than the ghost. "Lower deaths"
     * alone would flip back to the ghost; the statsOffset-anchored tracked.id
     * keeps us on the live slot.
     */
    @Test
    void dedupeByAuth_activeClientOvertakesGhostDeaths() {
        Simulator sim = new Simulator();

        // Round 1: slot 2 plays and ends at 2/3.
        sim.tick(List.of(makeRppPlayer("2", "jsn", "blue", 2, 3, 0)));

        // First dual-slot tick: fresh slot 10 appears at 0/0/0.
        sim.tick(List.of(
                makeRppPlayer("2", "jsn", "blue", 2, 3, 0),
                makeRppPlayer("10", "jsn", "blue", 0, 0, 0)));

        // Rounds 2+: slot 10 keeps growing, eventually past slot 2's frozen 3 deaths.
        sim.tick(List.of(
                makeRppPlayer("2", "jsn", "blue", 2, 3, 0),
                makeRppPlayer("10", "jsn", "blue", 5, 4, 1)));
        sim.tick(List.of(
                makeRppPlayer("2", "jsn", "blue", 2, 3, 0),
                makeRppPlayer("10", "jsn", "blue", 8, 6, 2)));

        CTF_Stats jsn = sim.savedStats.get("jsn");
        assertEquals(2 + 8, jsn.score);
        assertEquals(3 + 6, jsn.deaths);
        assertEquals(0 + 2, jsn.assists);

        // The tracked player should be on the live slot (10), not reverted to 2.
        ServerPlayer tracked = sim.players.stream()
                .filter(p -> "jsn".equals(p.auth)).findFirst().orElseThrow();
        assertEquals("10", tracked.id, "must stay on the live slot even after deaths overtake the ghost");
    }
}
