package de.gost0r.pickupbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;

@EnableRetry
@SpringBootApplication
public class PickupBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(PickupBotApplication.class, args);
    }
}
