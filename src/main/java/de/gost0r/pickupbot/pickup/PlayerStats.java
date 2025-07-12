package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.pickup.stats.WinDrawLoss;

public class PlayerStats {
    // TS
    public int kills = 0;
    public int deaths = 0;
    public int assists = 0;
    public float kdr = 0.0f;
    public int kdrRank = 0;
    public WinDrawLoss ts_wdl = new WinDrawLoss();
    public int wdlRank = 0;

    // CTF
    public WinDrawLoss ctf_wdl = new WinDrawLoss();
    public int ctfWdlRank = 0;
    public float ctf_rating = 0.f;
    public int ctfRank = 0;
    public int caps = 0;
    public int returns = 0;
    public int fckills = 0;
    public int stopcaps = 0;
    public int protflag = 0;
}