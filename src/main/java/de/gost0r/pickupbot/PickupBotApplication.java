package de.gost0r.pickupbot;

import io.sentry.Sentry;
import de.gost0r.pickupbot.config.SentryEventGrouping;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.retry.annotation.EnableRetry;

import java.util.Arrays;

@EnableRetry
@SpringBootApplication
public class PickupBotApplication {

    public static void main(String[] args) {
        initializeSentry();
        if (Arrays.asList(args).contains("--sentry-test")) {
            runSentryTest();
            return;
        }
        try {
            SpringApplication.run(PickupBotApplication.class, args);
        } catch (RuntimeException | Error failure) {
            Sentry.captureException(failure);
            Sentry.flush(2000);
            throw failure;
        }
    }

    static void initializeSentry() {
        // Load classpath defaults, sentry.properties, system properties and SENTRY_* overrides
        // before Spring, Discord or worker threads can fail. SDK shutdown/uncaught handlers
        // are enabled by default.
        Sentry.init(options -> {
            options.setEnableExternalConfiguration(true);
            options.setBeforeSend((event, hint) -> SentryEventGrouping.apply(event));
        });
    }

    private static void runSentryTest() {
        if (!Sentry.isEnabled()) {
            throw new IllegalStateException("Sentry is disabled; configure SENTRY_DSN before testing GlitchTip");
        }
        LoggingSystem logging = LoggingSystem.get(PickupBotApplication.class.getClassLoader());
        logging.beforeInitialize();
        logging.initialize(new LoggingInitializationContext(new StandardEnvironment()),
                "classpath:logback-spring.xml", null);
        try {
            var logger = LoggerFactory.getLogger(PickupBotApplication.class);
            logger.warn("Test GlitchTip warning!", new IllegalArgumentException("Test GlitchTip warning!"));
            for (int matchId = 1; matchId <= 2; matchId++) {
                logger.error("Test GlitchTip error for match {}!", matchId,
                        new IllegalStateException("Test GlitchTip error for match " + matchId + "!"));
                System.out.println("GlitchTip test error event ID: " + Sentry.getLastEventId());
            }
            Sentry.flush(2000);
        } finally {
            Sentry.close();
            logging.cleanUp();
        }
    }
}
