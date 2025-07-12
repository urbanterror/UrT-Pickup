package de.gost0r.pickupbot.pickup.server;

public class CTF_Stats {

    public int score = 0;
    public int deaths = 0;
    public int assists = 0;

    public int caps = 0;
    public int returns = 0;
    public int fc_kills = 0;
    public int stop_caps = 0;
    public int protect_flag = 0;

    public void add(CTF_Stats inStats) {
        this.score += inStats.score;
        this.deaths += inStats.deaths;
        this.assists += inStats.assists;

        this.caps += inStats.caps;
        this.returns += inStats.returns;
        this.fc_kills += inStats.fc_kills;
        this.stop_caps += inStats.stop_caps;
        this.protect_flag += inStats.protect_flag;
    }

}
