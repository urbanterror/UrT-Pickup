package de.gost0r.pickupbot.pickup;

import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight read-only snapshot of a match, used for display purposes (e.g. !last, !match).
 * Avoids constructing full Player/Match object graphs just to render an embed.
 */
public class MatchSummary {

    public final int id;
    public final long starttime;
    public final String map;
    public final String gametype;
    public final int scoreRed;
    public final int scoreBlue;
    public final String state;
    public final int serverId;
    public final List<PlayerLine> players;

    public MatchSummary(int id, long starttime, String map, String gametype,
                        int scoreRed, int scoreBlue, String state, int serverId) {
        this.id = id;
        this.starttime = starttime;
        this.map = map;
        this.gametype = gametype;
        this.scoreRed = scoreRed;
        this.scoreBlue = scoreBlue;
        this.state = state;
        this.serverId = serverId;
        this.players = new ArrayList<>();
    }

    /** One row per player in the match, pre-aggregated from the two score halves. */
    public static class PlayerLine {
        public final String urtauth;
        public final String team;
        public final String country;
        public final int kills;
        public final int deaths;
        public final int assists;

        public PlayerLine(String urtauth, String team, String country,
                          int kills, int deaths, int assists) {
            this.urtauth = urtauth;
            this.team = team;
            this.country = country;
            this.kills = kills;
            this.deaths = deaths;
            this.assists = assists;
        }
    }
}
