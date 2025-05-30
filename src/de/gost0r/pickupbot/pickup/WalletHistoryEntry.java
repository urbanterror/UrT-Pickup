package de.gost0r.pickupbot.pickup;

public class WalletHistoryEntry {
    private int seasonNumber;
    private long balance;
    
    public WalletHistoryEntry(int seasonNumber, long balance) {
        this.seasonNumber = seasonNumber;
        this.balance = balance;
    }
    
    public int getSeasonNumber() {
        return seasonNumber;
    }
    
    public long getBalance() {
        return balance;
    }
}