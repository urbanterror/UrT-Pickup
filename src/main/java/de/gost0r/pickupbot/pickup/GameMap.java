package de.gost0r.pickupbot.pickup;

import java.util.HashMap;
import java.util.Map;

public class GameMap {

    public String name;
    public long bannedUntil;
    public Map<Gametype, Boolean> gametypeList = new HashMap<Gametype, Boolean>();

    public GameMap(String name) {
        this.name = name;
        this.bannedUntil = 0;
    }

    public void setGametype(Gametype gametype, boolean active) {
        gametypeList.put(gametype, active);
    }

    public boolean isActiveForGametype(Gametype gametype) {
        if (gametypeList.containsKey(gametype)) {
            return gametypeList.get(gametype);
        }
        return false;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof GameMap) {
            GameMap other = (GameMap) obj;
            return other.name == this.name;
        }
        return false;
    }

    @Override
    public String toString() {
        return name;
    }
}
