package de.gost0r.pickupbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.sentry.Sentry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.core.env.StandardEnvironment;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

class SentryIntegrationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void realLoggingAndUncaughtHandlersDeliverEventsWithStableGrouping() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var received = new LinkedBlockingQueue<JsonNode>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/37/envelope/", exchange -> {
            try (var body = "gzip".equals(exchange.getRequestHeaders().getFirst("Content-Encoding"))
                    ? new GZIPInputStream(exchange.getRequestBody()) : exchange.getRequestBody()) {
                String[] lines = new String(body.readAllBytes(), StandardCharsets.UTF_8).split("\n");
                for (int i = 1; i + 1 < lines.length; i += 2) {
                    if ("event".equals(mapper.readTree(lines[i]).path("type").asText())) {
                        received.add(mapper.readTree(lines[i + 1]));
                    }
                }
                exchange.sendResponseHeaders(200, -1);
            } finally {
                exchange.close();
            }
        });
        server.start();
        String previousDsn = System.getProperty("sentry.dsn");
        String previousLogFile = System.getProperty("LOG_FILE");
        Thread.UncaughtExceptionHandler previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        LoggingSystem logging = LoggingSystem.get(getClass().getClassLoader());
        try {
            // Exercise the real startup path and real HTTP transport without contacting GlitchTip.
            System.setProperty("sentry.dsn", "http://test@127.0.0.1:" + server.getAddress().getPort() + "/37");
            System.setProperty("LOG_FILE", temporaryDirectory.resolve("bot.log").toString());
            Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> { });
            PickupBotApplication.initializeSentry();
            assertTrue(Sentry.isEnabled());
            var options = Sentry.getCurrentScopes().getOptions();
            assertEquals(0.01, options.getTracesSampleRate());
            assertEquals(1.0, options.getSampleRate());
            assertTrue(options.getInAppIncludes().contains("de.gost0r.pickupbot"));
            logging.beforeInitialize();
            logging.initialize(new LoggingInitializationContext(new StandardEnvironment()),
                    "classpath:logback-spring.xml", null);

            var logger = LoggerFactory.getLogger(SentryIntegrationTest.class);
            logger.info("Ordinary progress");
            var warningFailure = failure("monitor", "warning for player 1", 10);
            logger.warn("Handled warning", warningFailure);
            Sentry.captureException(warningFailure); // The same Throwable must not be counted twice.
            logger.error("Match {} failed", 1, new RuntimeException("wrapper 1", failure("save", "match 1", 20)));
            logger.error("Match {} failed", 2, new IllegalArgumentException("wrapper 2", failure("save", "match 2", 99)));
            Sentry.captureException(failure("save", "match 3", 123));
            logger.error("Different origin", failure("load", "match 4", 20));
            logger.error("Different type", new UnsupportedOperationException("unsupported"));
            logger.error("Server {} unavailable", 100);
            logger.error("Server {} unavailable", 200);
            Thread worker = new Thread(() -> { throw failure("worker", "uncaught", 30); });
            worker.start();
            worker.join(5000);
            assertFalse(worker.isAlive());
            Sentry.flush(5000);

            JsonNode warning = event(received);
            JsonNode firstMatch = event(received);
            JsonNode secondMatch = event(received);
            JsonNode directMatch = event(received);
            JsonNode otherOrigin = event(received);
            JsonNode otherType = event(received);
            JsonNode firstMessage = event(received);
            JsonNode secondMessage = event(received);
            JsonNode uncaught = event(received);
            assertEquals("warning", warning.path("level").asText());
            assertEquals("error", firstMatch.path("level").asText());
            assertEquals(firstMatch.get("fingerprint"), secondMatch.get("fingerprint"));
            assertEquals(firstMatch.get("fingerprint"), directMatch.get("fingerprint"));
            assertNotEquals(firstMatch.get("fingerprint"), otherOrigin.get("fingerprint"));
            assertNotEquals(firstMatch.get("fingerprint"), otherType.get("fingerprint"));
            assertEquals(firstMessage.get("fingerprint"), secondMessage.get("fingerprint"));
            assertNotEquals(firstMessage.path("message").path("formatted"),
                    secondMessage.path("message").path("formatted"));
            assertEquals("Server {} unavailable", firstMessage.path("message").path("message").asText());
            assertEquals("match 1", firstMatch.path("exception").path("values").get(0).path("value").asText());
            assertFalse(uncaught.path("exception").path("values").get(0)
                    .path("mechanism").path("handled").asBoolean(true));
            assertTrue(warning.path("breadcrumbs").toString().contains("Ordinary progress"));
            assertNull(received.poll(200, TimeUnit.MILLISECONDS),
                    "INFO should only be a breadcrumb and the same Throwable should not be reported twice");
        } finally {
            Sentry.close();
            logging.cleanUp();
            Thread.setDefaultUncaughtExceptionHandler(previousHandler);
            restoreProperty("sentry.dsn", previousDsn);
            restoreProperty("LOG_FILE", previousLogFile);
            server.stop(0);
        }
    }

    @Test
    void missingOrEmptyDsnKeepsReportingDisabled() {
        String previous = System.getProperty("sentry.dsn");
        try {
            System.clearProperty("sentry.dsn");
            PickupBotApplication.initializeSentry();
            assertFalse(Sentry.isEnabled());
            System.setProperty("sentry.dsn", "");
            PickupBotApplication.initializeSentry();
            assertFalse(Sentry.isEnabled());
        } finally {
            Sentry.close();
            restoreProperty("sentry.dsn", previous);
        }
    }

    private static IllegalStateException failure(String method, String message, int line) {
        var failure = new IllegalStateException(message);
        failure.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("de.gost0r.pickupbot.pickup.Database", method, "Database.java", line)
        });
        return failure;
    }

    private static JsonNode event(LinkedBlockingQueue<JsonNode> received) throws InterruptedException {
        JsonNode event = received.poll(5, TimeUnit.SECONDS);
        assertNotNull(event, "Expected an event delivered by the real Sentry HTTP transport");
        assertTrue(event.path("fingerprint").isArray());
        return event;
    }

    private static void restoreProperty(String key, String previous) {
        if (previous == null) System.clearProperty(key);
        else System.setProperty(key, previous);
    }
}
