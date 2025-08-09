package de.gost0r.pickupbot;

import de.gost0r.pickupbot.pickup.Country;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Locale;

@Slf4j
@Configuration
@EnableScheduling
public class SetupConfiguration {

    @PostConstruct
    public void init() {
        Locale.setDefault(Locale.ENGLISH);

        Country.initCountryCodes();

        log.info("Bot started.");
    }
}
