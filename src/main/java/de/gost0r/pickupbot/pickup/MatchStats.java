package de.gost0r.pickupbot.pickup;

public class MatchStats {

    public enum Status {
        PLAYING,
        LEFT,
        NOSHOW,
        RAGEQUIT
    }

    private String ip;
    private Status status = Status.NOSHOW;

    public Score[] score = new Score[2];

    public MatchStats() {
        score = new Score[2];
        score[0] = new Score();
        score[1] = new Score();
    }

    public MatchStats(Score score1, Score score2, String ip, Status status) {
        this();
        score[0] = score1;
        score[1] = score2;
        this.ip = ip;
        this.status = status;
    }

    public void updateStatus(Status status) {
        this.status = status;
    }

    public Status getStatus() {
        return status;
    }

    public void updateIP(String ip) {
        this.ip = ip;
    }

    public String getIP() {
        return ip;
    }

}
