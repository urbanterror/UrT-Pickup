package de.gost0r.pickupbot.ftwgl.models;

/** FTW's primary (since July 3, 2025) and current-season ratings. */
public record PlayerRating(float allTime, float season) {
    public static final PlayerRating ZERO = new PlayerRating(0f, 0f);

    public float captainRating() {
        return Math.max(allTime, season);
    }

    public String display() {
        return String.format("%.02f (season) / %.02f (all-time)", season, allTime);
    }
}
