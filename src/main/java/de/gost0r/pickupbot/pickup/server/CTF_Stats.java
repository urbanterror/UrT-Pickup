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

    public boolean hasTrackedStats() {
        return score != 0
                || deaths != 0
                || assists != 0
                || caps != 0
                || returns != 0
                || fc_kills != 0
                || stop_caps != 0
                || protect_flag != 0;
    }

    /** True if all tracked stat fields match (server snapshot equals accumulated offset). */
    public boolean totalsEqual(CTF_Stats o) {
        if (o == null) {
            return false;
        }
        return score == o.score
                && deaths == o.deaths
                && assists == o.assists
                && caps == o.caps
                && returns == o.returns
                && fc_kills == o.fc_kills
                && stop_caps == o.stop_caps
                && protect_flag == o.protect_flag;
    }

    /**
     * True if this stats object has values >= the other for all primary fields (score/deaths/assists).
     * Used to detect when server preserved cumulative stats across reconnect.
     * If server stats >= offset, the server is tracking correctly and we should clear offset.
     */
    public boolean atLeastAs(CTF_Stats o) {
        if (o == null) {
            return true;
        }
        return score >= o.score
                && deaths >= o.deaths
                && assists >= o.assists;
    }

}
