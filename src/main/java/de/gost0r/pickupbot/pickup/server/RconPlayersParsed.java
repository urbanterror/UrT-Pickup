package de.gost0r.pickupbot.pickup.server;

import java.util.ArrayList;
import java.util.List;

public class RconPlayersParsed {

    public String map;
    public int playercount;
    public String gametype;
    public int[] scores = new int[2];
    public boolean matchmode;
    public boolean[] matchready = new boolean[2];
    public boolean warmupphase;
    public String gametime;
    public String roundtime;
    public String half;

    public List<ServerPlayer> players = new ArrayList<ServerPlayer>();

}
