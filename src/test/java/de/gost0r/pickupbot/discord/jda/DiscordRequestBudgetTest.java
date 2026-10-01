package de.gost0r.pickupbot.discord.jda;

import okhttp3.Interceptor;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiscordRequestBudgetTest {
    @TempDir Path directory;
    private final MutableClock clock = new MutableClock();

    @Test
    void responseFeedbackAndCountersSurviveRestart() throws Exception {
        DiscordRequestBudget budget = new DiscordRequestBudget(directory, clock);
        budget.recordRequest();
        budget.recordResponse(429, "0", "1.5", "3.25");
        budget.reserveRename("guild");
        budget.flush();

        DiscordRequestBudget restarted = new DiscordRequestBudget(directory, clock);
        assertFalse(restarted.tryAcquire());
        assertFalse(restarted.canRename("guild"));
        assertTrue(restarted.prometheus().contains("urt_discord_requests_total 1\n"));
        assertTrue(restarted.prometheus().contains("urt_discord_rate_limited_total 1\n"));
        assertTrue(Files.readString(directory.resolve("discord.prom")).contains("# TYPE urt_discord_requests_total counter"));
        clock.advance(4_249);
        assertFalse(restarted.tryAcquire());
        clock.advance(1);
        assertTrue(restarted.tryAcquire());
        clock.advance(295_749);
        assertFalse(restarted.canRename("guild"));
        clock.advance(1);
        assertTrue(restarted.canRename("guild"));
    }

    @Test
    void fiveMinuteRenameSpacingStillHonorsLongerDiscordBackoff() throws Exception {
        DiscordRequestBudget budget = new DiscordRequestBudget(directory, clock);
        budget.reserveRename("guild");
        budget.recordResponse(429, "0", "600", "600");
        clock.advance(300_000);
        assertTrue(budget.canRename("guild"));
        assertFalse(budget.tryAcquire());
        clock.advance(301_000);
        assertTrue(budget.tryAcquire());
    }

    @Test
    void liveChannelIdentitySurvivesRestartAndIsScopedToBotAndGuild() throws Exception {
        DiscordRequestBudget budget = new DiscordRequestBudget(directory, clock);
        budget.rememberLiveChannel("100", "200", "300");
        DiscordRequestBudget restarted = new DiscordRequestBudget(directory, clock);
        assertEquals("300", restarted.liveChannelId("100", "200"));
        assertNull(restarted.liveChannelId("101", "200"));
        assertNull(restarted.liveChannelId("100", "201"));
    }

    @Test
    void failedIdentityCheckpointDoesNotLeaveAnUnpersistedOwnershipClaim() throws Exception {
        DiscordRequestBudget budget = new DiscordRequestBudget(directory, clock);
        Files.createDirectory(directory.resolve("state.properties.tmp"));
        assertThrows(java.io.IOException.class, () -> budget.rememberLiveChannel("100", "200", "300"));
        assertNull(budget.liveChannelId("100", "200"));
        Files.delete(directory.resolve("state.properties.tmp"));
        budget.rememberLiveChannel("100", "200", "300");
        assertEquals("300", new DiscordRequestBudget(directory, clock).liveChannelId("100", "200"));
    }

    @Test
    void throttlesLiveWorkAndAllowsItAgainAfterTrafficWindowExpires() {
        DiscordRequestBudget budget = new DiscordRequestBudget(directory, clock);
        assertTrue(budget.tryAcquire());
        assertFalse(budget.tryAcquire());
        clock.advance(2_000);
        assertTrue(budget.tryAcquire());
        clock.advance(2_000);
        for (int i = 0; i < 600; i++) budget.recordRequest();
        assertFalse(budget.tryAcquire());
        assertEquals(60_000, budget.refreshMillis(1));
        assertEquals(160_000, budget.refreshMillis(20));
        clock.advance(60_000);
        assertTrue(budget.tryAcquire());
        assertEquals(30_000, budget.refreshMillis(1));
    }

    @Test
    void exhaustedRouteWithout429AlsoPausesPreviews() {
        DiscordRequestBudget budget = new DiscordRequestBudget(directory, clock);
        budget.recordResponse(200, "0", "12.5", null);
        assertFalse(budget.tryAcquire());
        clock.advance(13_500);
        assertTrue(budget.tryAcquire());
        assertTrue(budget.prometheus().contains("urt_discord_rate_limited_total 0\n"));
    }

    @Test
    void malformedRetryHeadersFallBackToConservativePause() {
        DiscordRequestBudget budget = new DiscordRequestBudget(directory, clock);
        budget.recordResponse(429, null, "NaN", "bad");
        assertFalse(budget.tryAcquire());
        clock.advance(61_000);
        assertTrue(budget.tryAcquire());
    }

    @Test
    void interceptorObservesActualDiscordResponsesWithoutConsumingThem() throws Exception {
        DiscordRequestBudget budget = new DiscordRequestBudget(directory, clock);
        Interceptor.Chain chain = mock(Interceptor.Chain.class);
        Request request = new Request.Builder().url("https://discord.com/api/v10/channels/123/messages").build();
        Response response = new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(429).message("rate limited").header("Retry-After", "7").build();
        when(chain.request()).thenReturn(request);
        when(chain.proceed(request)).thenReturn(response);
        assertSame(response, budget.intercept(chain));
        assertFalse(budget.tryAcquire());
        assertTrue(budget.prometheus().contains("urt_discord_requests_total 1\n"));
        assertTrue(budget.prometheus().contains("urt_discord_rate_limited_total 1\n"));
    }

    @Test
    void corruptStateIsNotSilentlyOverwritten() throws Exception {
        Path state = directory.resolve("state.properties");
        Files.writeString(state, "requests_total=broken\n");
        assertThrows(IllegalStateException.class, () -> new DiscordRequestBudget(directory, clock));
        assertEquals("requests_total=broken\n", Files.readString(state));
    }

    @Test
    void failedCheckpointPreservesLastGoodFilesAndCanBeRetried() throws Exception {
        DiscordRequestBudget budget = new DiscordRequestBudget(directory, clock);
        budget.recordRequest();
        budget.flush();
        String lastState = Files.readString(directory.resolve("state.properties"));
        String lastMetrics = Files.readString(directory.resolve("discord.prom"));
        Files.createDirectory(directory.resolve("state.properties.tmp"));
        budget.recordRequest();
        assertDoesNotThrow(budget::flush);
        assertEquals(lastState, Files.readString(directory.resolve("state.properties")));
        assertEquals(lastMetrics, Files.readString(directory.resolve("discord.prom")));
        DiscordRequestBudget restarted = new DiscordRequestBudget(directory, clock);
        assertTrue(restarted.prometheus().contains("urt_discord_requests_total 1\n"));
        Files.delete(directory.resolve("state.properties.tmp"));
        budget.flush();
        restarted = new DiscordRequestBudget(directory, clock);
        assertTrue(restarted.prometheus().contains("urt_discord_requests_total 2\n"));
        assertTrue(Files.readString(directory.resolve("discord.prom")).contains("urt_discord_requests_total 2\n"));
    }

    @Test
    void abandonedPartialCheckpointIsIgnoredAndNextFlushReplacesIt() throws Exception {
        DiscordRequestBudget budget = new DiscordRequestBudget(directory, clock);
        budget.recordRequest();
        budget.recordResponse(429, "0", "30", "30");
        budget.reserveRename("guild");
        budget.flush();
        // Simulate a crash after writing part of the temporary file, before atomic replacement.
        Files.writeString(directory.resolve("state.properties.tmp"), "requests_total=broken");
        Files.writeString(directory.resolve("discord.prom.tmp"), "urt_discord_requests_total ");
        DiscordRequestBudget restarted = new DiscordRequestBudget(directory, clock);
        assertFalse(restarted.tryAcquire());
        assertFalse(restarted.canRename("guild"));
        assertTrue(restarted.prometheus().contains("urt_discord_requests_total 1\n"));
        restarted.recordRequest();
        restarted.flush();
        assertFalse(Files.exists(directory.resolve("state.properties.tmp")));
        assertFalse(Files.exists(directory.resolve("discord.prom.tmp")));
        assertTrue(new DiscordRequestBudget(directory, clock).prometheus().contains("urt_discord_requests_total 2\n"));
    }

    @Test
    void metricsWriteFailureDoesNotLoseCheckpointedCounters() throws Exception {
        DiscordRequestBudget budget = new DiscordRequestBudget(directory, clock);
        budget.recordRequest();
        budget.flush();
        String oldMetrics = Files.readString(directory.resolve("discord.prom"));
        Files.createDirectory(directory.resolve("discord.prom.tmp"));
        budget.recordRequest();
        budget.flush();
        assertEquals(oldMetrics, Files.readString(directory.resolve("discord.prom")));
        DiscordRequestBudget restarted = new DiscordRequestBudget(directory, clock);
        assertTrue(restarted.prometheus().contains("urt_discord_requests_total 2\n"));
        Files.delete(directory.resolve("discord.prom.tmp"));
        restarted.flush();
        assertTrue(Files.readString(directory.resolve("discord.prom")).contains("urt_discord_requests_total 2\n"));
    }

    @Test
    void networkFailureIsCountedWithoutInventingAResponse() throws Exception {
        DiscordRequestBudget budget = new DiscordRequestBudget(directory, clock);
        Interceptor.Chain chain = mock(Interceptor.Chain.class);
        Request request = new Request.Builder().url("https://discord.com/api/v10/channels/123/messages").build();
        when(chain.request()).thenReturn(request);
        java.io.IOException failure = new java.io.IOException("Connection reset");
        when(chain.proceed(request)).thenThrow(failure);
        assertSame(failure, assertThrows(java.io.IOException.class, () -> budget.intercept(chain)));
        assertTrue(budget.prometheus().contains("urt_discord_requests_total 1\n"));
        assertTrue(budget.prometheus().contains("urt_discord_network_errors_total 1\n"));
        assertTrue(budget.prometheus().contains("urt_discord_responses_total 0\n"));
    }

    private static class MutableClock extends Clock {
        private long millis = 1_000_000;
        void advance(long amount) { millis += amount; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
    }
}
