package de.gost0r.pickupbot.pickup.server;

import de.gost0r.pickupbot.pickup.Player;

public class ServerPlayer {

    public enum ServerPlayerState {
        Connecting,
        Connected,
        Disconnected,
        Reconnected
    }

    public ServerPlayerState state;
    public String id;
    public String name;
    public String team;
    public String ping;
    public String ip;
    public String auth;
    public Player player = null;
    public CTF_Stats ctfstats;
    public CTF_Stats statsOffset;

    public long timeDisconnect = -1L;

    public ServerPlayer() {
        ctfstats = new CTF_Stats();
        statsOffset = new CTF_Stats();
    }

    public void copy(ServerPlayer other) {
        this.state = other.state;
        this.id = other.id;
        this.name = other.name;
        this.team = other.team;
        this.ping = other.ping;
        this.ip = other.ip;
        this.auth = other.auth;
//		this.player = other.player; // don't override player reference
        this.ctfstats = other.ctfstats;
        this.timeDisconnect = other.timeDisconnect;
    }

    /**
     * Call after merging a fresh RCON {@link #ctfstats} snapshot. If the game kept cumulative
     * totals across reconnect, live stats are >= {@link #statsOffset} and summing both in
     * {@code saveStats} would double-count K/D/A.
     *
     * Uses >= comparison instead of exact equality to handle the case where the player
     * got a few more kills between disconnect detection and reconnect (race condition).
     * If server stats >= offset, the server preserved cumulative stats and we clear offset.
     * If server stats < offset, the server reset stats to 0 and we keep offset.
     */
    public void clearStatsOffsetIfServerSnapshotMatchesOffset() {
        if (statsOffset.hasTrackedStats() && ctfstats.atLeastAs(statsOffset)) {
            statsOffset = new CTF_Stats();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof ServerPlayer) {
            ServerPlayer p = (ServerPlayer) o;
            if (p.auth == null || p.auth.isEmpty() || p.auth.equals("---")) {
                // don't compare if the auth is not set
                return false;
            }
            return p.auth.equals(auth);// && p.ip.equals(ip);
        }
        return false;
    }
}
