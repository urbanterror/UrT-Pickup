package de.gost0r.pickupbot.pickup;

public class MatchPersistenceException extends RuntimeException {
    public MatchPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }

    public MatchPersistenceException(String message) {
        super(message);
    }
}
