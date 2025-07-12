package de.gost0r.pickupbot.pickup.stats;

public class WinDrawLoss {

    public int win = 0;
    public int draw = 0;
    public int loss = 0;

    public double calcWinRatio() {
        double d_win = (double) win;
        double d_draw = (double) draw;
        double d_loss = (double) loss;
        double d_total = d_win + d_draw + d_loss;

        if (d_total == 0.0d) return 0d;

        return (d_win + d_draw * 0.5d) / (d_total);
    }

    public int getTotal() {
        return win + draw + loss;
    }

}
