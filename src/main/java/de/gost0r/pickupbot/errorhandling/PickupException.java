package de.gost0r.pickupbot.errorhandling;

public class PickupException extends RuntimeException {
    public PickupException(String message) {
        super(message);
    }
}
