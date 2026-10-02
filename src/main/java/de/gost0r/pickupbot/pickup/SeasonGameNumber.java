package de.gost0r.pickupbot.pickup;

/** Immutable numbering assigned when a match is created, independent of its final result. */
public record SeasonGameNumber(int season, int gameNumber) {
    public String label() {
        return "Season " + season + ": Game #" + gameNumber;
    }
}
