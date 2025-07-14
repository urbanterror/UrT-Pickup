package de.gost0r.pickupbot.ftwgl;

import lombok.Getter;

@Getter
public class RetryableHttpException extends RuntimeException {
    private final int statusCode;

    public RetryableHttpException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

}
