package de.gost0r.pickupbot.pickup;

import java.util.Locale;

/** Immutable numbering assigned when a match is created, independent of its final result. */
public record SeasonGameNumber(int season, int gameNumber) {
    public String label(String gametype) {
        return "Season " + season + ": " + gametype.toUpperCase(Locale.ROOT) + " GAME #" + gameNumber;
    }
}
