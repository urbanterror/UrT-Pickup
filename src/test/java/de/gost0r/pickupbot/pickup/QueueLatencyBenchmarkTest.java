package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.discord.DiscordChannel;
import de.gost0r.pickupbot.discord.DiscordMessage;
import de.gost0r.pickupbot.discord.DiscordService;
import de.gost0r.pickupbot.discord.DiscordUser;
import de.gost0r.pickupbot.ftwgl.FtwglApi;
import de.gost0r.pickupbot.permission.PermissionService;
import de.gost0r.pickupbot.permission.PickupRoleCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Run explicitly with ./gradlew test --tests '*QueueLatencyBenchmarkTest' --info. */
@Isolated("Temporarily replaces Player's static database and cache")
class QueueLatencyBenchmarkTest {
    @Test
    void repeatedAlreadyQueuedJoinsSkipAllFtwRequests() throws Exception {
        FtwglApi ftw = mock(FtwglApi.class);
        PickupBot bot = new PickupBot("benchmark", ftw, mock(DiscordService.class),
                mock(PermissionService.class), mock(PickupRoleCache.class),
                Runnable::run, Runnable::run, Runnable::run, Runnable::run);
        PickupLogic logic = new PickupLogic(bot, ftw, mock(DiscordService.class),
                mock(PermissionService.class), mock(PickupRoleCache.class));
        Player player = mock(Player.class);
        DiscordUser queuedUser = user("already-queued");
        when(player.getDiscordUser()).thenReturn(queuedUser);
        Gametype ts = new Gametype("TS", 5, true, false);
        Match match = mock(Match.class);
        when(match.isInMatch(player)).thenReturn(true);
        field(logic, "curMatch", Map.of(ts, match));

        int repetitions = 1000;
        long start = System.nanoTime();
        for (int i = 0; i < repetitions; i++) {
            logic.queueAddPlayer(player, List.of(ts), false,
                    reply -> assertEquals("You are already queued for: TS", reply.getMessage()));
        }
        double averageMs = (double) TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - start) / repetitions / 1000;
        System.out.printf("Already-queued !ts: %d repetitions, average %.3f ms, FTW calls=0%n", repetitions, averageMs);
        verifyNoInteractions(ftw);
    }

    @Test
    void coldPlayerLoadDoesNotDelayAnUnrelatedQueueCommand() throws Exception {
        Field cache = Player.class.getDeclaredField("playerList");
        cache.setAccessible(true);
        Object originalCache;
        Database originalDatabase;
        synchronized (Player.class) {
            originalCache = cache.get(null);
            originalDatabase = Player.db;
            cache.set(null, new ArrayList<Player>());
        }
        ExecutorService loaders = Executors.newFixedThreadPool(2);
        ExecutorService queue = Executors.newSingleThreadExecutor();
        CountDownLatch release = new CountDownLatch(1);
        try {
            Database database = mock(Database.class);
            Player.db = database;
            DiscordUser cold = user("cold-player");
            DiscordUser warm = user("warm-player");
            Player warmPlayer = new Player(warm, "warm-auth");
            CountDownLatch coldStarted = new CountDownLatch(1);
            when(database.loadPlayer(cold)).thenAnswer(invocation -> {
                coldStarted.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return null;
            });
            PickupLogic logic = mock(PickupLogic.class);
            DiscordChannel channel = mock(DiscordChannel.class);
            when(channel.getName()).thenReturn("pickup");
            when(logic.getChannelByType(PickupChannelType.PUBLIC)).thenReturn(List.of(channel));
            when(logic.cmdRemovePlayer(warmPlayer, null)).thenReturn(new PickupReply("removed"));
            PickupBot bot = new PickupBot("benchmark", mock(FtwglApi.class), mock(DiscordService.class),
                    mock(PermissionService.class), mock(PickupRoleCache.class), loaders, queue,
                    Runnable::run, Runnable::run);
            field(bot, "logic", logic);
            field(bot, "self", user("bot"));
            CountDownLatch warmFinished = new CountDownLatch(1);
            AtomicLong warmAt = new AtomicLong();
            DiscordMessage warmRemove = message("!remove", warm, channel);
            doAnswer(invocation -> {
                warmAt.set(System.nanoTime());
                warmFinished.countDown();
                return null;
            }).when(warmRemove).reply("removed");
            CountDownLatch coldFinished = new CountDownLatch(1);
            AtomicLong coldAt = new AtomicLong();
            DiscordMessage coldJoin = message("!ts", cold, channel);
            doAnswer(invocation -> {
                coldAt.set(System.nanoTime());
                coldFinished.countDown();
                return null;
            }).when(coldJoin).reply(Config.user_not_registered);

            long coldStart = System.nanoTime();
            bot.recvMessage(coldJoin);
            assertTrue(coldStarted.await(2, TimeUnit.SECONDS));
            long warmStart = System.nanoTime();
            bot.recvMessage(warmRemove);
            assertTrue(warmFinished.await(2, TimeUnit.SECONDS));
            Thread.sleep(1000); // Simulate an external lookup that has not returned yet.
            assertEquals(1L, coldFinished.getCount());
            release.countDown();
            assertTrue(coldFinished.await(2, TimeUnit.SECONDS));

            long warmMs = TimeUnit.NANOSECONDS.toMillis(warmAt.get() - warmStart);
            long coldMs = TimeUnit.NANOSECONDS.toMillis(coldAt.get() - coldStart);
            System.out.printf("Queue latency, cold lookup held 1s: other player !remove=%d ms, cold !ts=%d ms%n", warmMs, coldMs);
            assertTrue(warmAt.get() < coldAt.get());
        } finally {
            release.countDown();
            loaders.shutdownNow();
            queue.shutdownNow();
            assertTrue(loaders.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(queue.awaitTermination(5, TimeUnit.SECONDS));
            synchronized (Player.class) {
                cache.set(null, originalCache);
                Player.db = originalDatabase;
            }
        }
    }

    private static DiscordUser user(String id) {
        DiscordUser user = mock(DiscordUser.class);
        when(user.getId()).thenReturn(id);
        when(user.getUsername()).thenReturn(id);
        return user;
    }

    private static DiscordMessage message(String text, DiscordUser sender, DiscordChannel channel) {
        DiscordMessage message = mock(DiscordMessage.class);
        when(message.getContent()).thenReturn(text);
        when(message.getUser()).thenReturn(sender);
        when(message.getChannel()).thenReturn(channel);
        return message;
    }

    private static void field(Object instance, String name, Object value) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }
}
