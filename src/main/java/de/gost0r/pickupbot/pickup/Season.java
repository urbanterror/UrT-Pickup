package de.gost0r.pickupbot.pickup;

public class Season {
    public int number;
    public long startdate;
    public long enddate;

    public Season(int number, long startdate, long enddate) {
        this.number = number;
        this.startdate = startdate;
        this.enddate = enddate;
    }

    public static Season AllTimeSeason() {
        return new Season(0, 0, System.currentTimeMillis());
    }
}
