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
     * Call BEFORE updating ctfstats with fresh server data.
     * Deaths never decrease in normal play: a TK only reduces score, a suicide
     * bumps deaths. A drop in deaths therefore signals that the server wiped the
     * player's tracker (reconnect). We key the check on deaths alone so that
     * TKs and suicides don't masquerade as resets and inflate the offset.
     *
     * @param serverStats the fresh stats from RCON
     * @return true if stats were reset (and preserved to offset), false otherwise
     */
    public boolean preserveStatsIfReset(CTF_Stats serverStats) {
        if (ctfstats.hasTrackedStats() && serverStats.deaths < ctfstats.deaths) {
            statsOffset.add(ctfstats);
            return true;
        }
        return false;
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
