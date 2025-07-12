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

    public long timeDisconnect = -1L;

    public ServerPlayer() {
        ctfstats = new CTF_Stats();
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
