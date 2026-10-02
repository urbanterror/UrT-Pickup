package de.gost0r.pickupbot.discord.jda;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Interceptor;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Properties;

/** Observes actual HTTP attempts, including JDA retries. JDA remains the rate-limit authority. */
@Slf4j
@Component
public class DiscordRequestBudget implements Interceptor {
    private final Path directory;
    private final Clock clock;
    private final Properties state = new Properties();
    private final ArrayDeque<Long> recentRequests = new ArrayDeque<>();
    private long nextRequest;
    private long refreshMillis = 30_000;

    @Autowired
    public DiscordRequestBudget(@Value("${app.discord.live-games.state-directory:./data/discord-live}") String directory) {
        this(Path.of(directory), Clock.systemUTC());
    }

    DiscordRequestBudget(Path directory, Clock clock) {
        this.directory = directory;
        this.clock = clock;
        Path file = directory.resolve("state.properties");
        if (Files.exists(file)) {
            try (var reader = Files.newBufferedReader(file)) {
                state.load(reader);
                // Validate before using the persisted counters and deadlines.
                for (String key : state.stringPropertyNames()) Long.parseLong(state.getProperty(key));
            } catch (IOException | IllegalArgumentException e) {
                throw new IllegalStateException("Cannot restore Discord request metrics from " + file, e);
            }
        }
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        String host = chain.request().url().host();
        if (!host.equals("discord.com") && !host.endsWith(".discord.com")) return chain.proceed(chain.request());
        recordRequest();
        try {
            Response response = chain.proceed(chain.request());
            recordResponse(response.code(), response.header("X-RateLimit-Remaining"),
                    response.header("X-RateLimit-Reset-After"), response.header("Retry-After"));
            return response;
        } catch (IOException e) {
            synchronized (this) { increment("network_errors_total"); }
            throw e;
        }
    }

    synchronized void recordRequest() {
        increment("requests_total");
        recentRequests.addLast(clock.millis());
        trimRequests();
    }

    synchronized void recordResponse(int status, String remaining, String resetAfter, String retryAfter) {
        increment("responses_total");
        if (status >= 400) increment("errors_total");
        if (status == 429) increment("rate_limited_total");
        if (status == 429 || "0".equals(remaining)) {
            long delay = Math.max(seconds(resetAfter), seconds(retryAfter));
            if (delay == 0) delay = 60_000;
            put("blocked_until", Math.max(value("blocked_until"), clock.millis() + delay + 1_000));
        }
    }

    private static long seconds(String value) {
        try {
            double seconds = Double.parseDouble(value);
            return Double.isFinite(seconds) && seconds > 0 ? (long) Math.ceil(seconds * 1_000) : 0;
        } catch (NullPointerException | NumberFormatException e) {
            return 0;
        }
    }

    /** Low-priority live work: at most one operation every two seconds, with headroom for commands. */
    public synchronized boolean tryAcquire() {
        trimRequests();
        long now = clock.millis();
        if (now < nextRequest || now < value("blocked_until") || recentRequests.size() >= 600) {
            increment("live_deferred_total");
            return false;
        }
        nextRequest = now + 2_000;
        increment("live_operations_total");
        return true;
    }

    public synchronized long refreshMillis(int previews) {
        trimRequests();
        refreshMillis = Math.max(30_000L, previews * 4_000L);
        if (recentRequests.size() >= 300) refreshMillis *= 2;
        return refreshMillis;
    }

    public synchronized boolean canRename(String guildId) {
        return clock.millis() >= value("rename_after_" + guildId);
    }

    public synchronized String liveChannelId(String botId, String guildId) {
        return state.getProperty("live_channel_" + botId + "_" + guildId);
    }

    /** Checkpoint ownership before replacing the topic marker with a readable description. */
    public synchronized void rememberLiveChannel(String botId, String guildId, String channelId) throws IOException {
        String key = "live_channel_" + botId + "_" + guildId;
        String previous = state.getProperty(key);
        if (channelId.equals(previous)) return;
        state.setProperty(key, channelId);
        try {
            saveState();
        } catch (IOException e) {
            if (previous == null) state.remove(key);
            else state.setProperty(key, previous);
            throw e;
        }
    }

    /** Space renames five minutes apart; Discord response backoff still takes precedence. */
    public synchronized void reserveRename(String guildId) throws IOException {
        // Save before submission so a restart cannot bypass the spacing policy.
        put("rename_after_" + guildId, clock.millis() + 300_000);
        saveState();
    }

    public synchronized void failedOperation() {
        increment("live_errors_total");
        nextRequest = Math.max(nextRequest, clock.millis() + 30_000);
    }

    private void trimRequests() {
        long cutoff = clock.millis() - 60_000;
        while (!recentRequests.isEmpty() && recentRequests.peekFirst() <= cutoff) recentRequests.removeFirst();
    }

    private long value(String key) { return Long.parseLong(state.getProperty(key, "0")); }
    private void put(String key, long value) { state.setProperty(key, Long.toString(value)); }
    private void increment(String key) { put(key, value(key) + 1); }

    public synchronized String prometheus() {
        trimRequests();
        StringBuilder text = new StringBuilder();
        for (String counter : new String[]{"requests_total", "responses_total", "errors_total", "network_errors_total",
                "rate_limited_total", "live_operations_total", "live_deferred_total", "live_errors_total"}) {
            metric(text, counter, "counter", value(counter));
        }
        metric(text, "requests_last_minute", "gauge", recentRequests.size());
        metric(text, "live_refresh_interval_seconds", "gauge", refreshMillis / 1_000);
        metric(text, "live_blocked_until_seconds", "gauge", value("blocked_until") / 1_000);
        metric(text, "metrics_written_timestamp_seconds", "gauge", clock.millis() / 1_000);
        return text.toString();
    }

    private static void metric(StringBuilder text, String name, String type, long value) {
        text.append("# TYPE urt_discord_").append(name).append(' ').append(type).append('\n');
        text.append("urt_discord_").append(name).append(' ').append(value).append('\n');
    }

    @Scheduled(fixedDelay = 5_000)
    @PreDestroy
    public synchronized void flush() {
        try {
            saveState();
            atomicWrite(directory.resolve("discord.prom"), prometheus());
        } catch (IOException e) {
            log.error("Cannot persist Discord metrics in {}", directory, e);
        }
    }

    private void saveState() throws IOException {
        StringWriter writer = new StringWriter();
        state.store(writer, "Discord counters, live-channel IDs and metadata update deadlines");
        atomicWrite(directory.resolve("state.properties"), writer.toString());
    }

    private void atomicWrite(Path target, String content) throws IOException {
        Files.createDirectories(directory);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, content);
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
