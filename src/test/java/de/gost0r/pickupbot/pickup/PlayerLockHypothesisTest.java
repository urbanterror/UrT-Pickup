package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.discord.DiscordInteraction;
import de.gost0r.pickupbot.discord.DiscordMessage;
import de.gost0r.pickupbot.discord.DiscordService;
import de.gost0r.pickupbot.discord.DiscordUser;
import de.gost0r.pickupbot.ftwgl.FtwglApi;
import de.gost0r.pickupbot.permission.PermissionService;
import de.gost0r.pickupbot.permission.PickupRoleCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Regression coverage: unrelated cached lookups and queue work must progress while a cold
 * database load is held. Latches keep the database delay independent of lookup timing.
 */
@Isolated("Temporarily replaces Player's static cache and database")
class PlayerLockHypothesisTest {
    private static final long DEADLINE_SECONDS = 10;
    private final CountDownLatch releaseColdLoad = new CountDownLatch(1);
    private final CountDownLatch coldLoadEntered = new CountDownLatch(1);
    private final List<ExecutorService> executors = new ArrayList<>();
    private Field cacheField;
    private Object originalCache;
    private Database originalDatabase;
    private Database database;
    private DiscordUser warmUser;
    private DiscordUser coldUser;
    private Player warmPlayer;

    @BeforeEach
    void setUp() throws Exception {
        cacheField = Player.class.getDeclaredField("playerList");
        cacheField.setAccessible(true);
        synchronized (Player.class) {
            originalCache = cacheField.get(null);
            originalDatabase = Player.db;
            cacheField.set(null, new ArrayList<Player>());
            database = mock(Database.class);
            Player.db = database;
        }
        warmUser = user("warm-user");
        coldUser = user("unrelated-cold-user");
        warmPlayer = new Player(warmUser, "warm-auth");
    }

    @AfterEach
    void tearDown() throws Exception {
        // Always unblock before awaiting termination or touching Player.class again.
        releaseColdLoad.countDown();
        for (ExecutorService executor : executors) {
            executor.shutdownNow();
        }
        for (ExecutorService executor : executors) {
            assertTrue(executor.awaitTermination(DEADLINE_SECONDS, TimeUnit.SECONDS),
                    "All workers must stop before restoring static state");
        }
        synchronized (Player.class) {
            cacheField.set(null, originalCache);
            Player.db = originalDatabase;
        }
    }

    @Test
    void controlCachedLookupsCompleteWithoutDatabaseWorkWhenNoColdLoadIsPending() throws Exception {
        ExecutorService workers = executor(3);
        try {
            List<Future<Player>> lookups = List.of(
                    workers.submit(() -> Player.get("warm-auth")),
                    workers.submit(() -> Player.get(warmUser)),
                    workers.submit(() -> Player.get(warmUser, "warm-auth")));
            for (Future<Player> lookup : lookups) {
                assertSame(warmPlayer, lookup.get(DEADLINE_SECONDS, TimeUnit.SECONDS));
            }
            verifyNoInteractions(database);
        } finally {
            releaseColdLoad.countDown();
        }
    }

    @Test
    void controlSlowDatabaseCallOutsidePlayerGetDoesNotBlockCachedLookups() throws Exception {
        when(database.loadPlayer("cold-auth")).thenAnswer(invocation -> delayedColdLoad());
        ExecutorService workers = executor(4);
        try {
            Future<Player> cold = workers.submit(() -> database.loadPlayer("cold-auth"));
            await(coldLoadEntered, "Direct database call did not start");
            List<Future<Player>> lookups = List.of(
                    workers.submit(() -> Player.get("warm-auth")),
                    workers.submit(() -> Player.get(warmUser)),
                    workers.submit(() -> Player.get(warmUser, "warm-auth")));
            for (Future<Player> lookup : lookups) {
                assertSame(warmPlayer, lookup.get(DEADLINE_SECONDS, TimeUnit.SECONDS));
            }
            assertColdLoadStillHeld(cold);
            releaseColdLoad.countDown();
            assertNull(cold.get(DEADLINE_SECONDS, TimeUnit.SECONDS));
            verify(database).loadPlayer("cold-auth");
            verifyNoMoreInteractions(database);
        } finally {
            releaseColdLoad.countDown();
        }
    }

    @Test
    void coldAuthLoadAllowsAllUnrelatedCachedLookupOverloadsToComplete() throws Exception {
        when(database.loadPlayer("cold-auth")).thenAnswer(invocation -> delayedColdLoad());
        assertCachedLookupsProgress(() -> Player.get("cold-auth"));
        verify(database).loadPlayer("cold-auth");
        verifyNoMoreInteractions(database);
    }

    @Test
    void coldDiscordUserLoadAllowsAllUnrelatedCachedLookupOverloadsToComplete() throws Exception {
        when(database.loadPlayer(coldUser)).thenAnswer(invocation -> delayedColdLoad());
        assertCachedLookupsProgress(() -> Player.get(coldUser));
        verify(database).loadPlayer(coldUser);
        verifyNoMoreInteractions(database);
    }

    @Test
    void coldUserAndAuthLoadAllowsAllUnrelatedCachedLookupOverloadsToComplete() throws Exception {
        when(database.loadPlayer(coldUser, "cold-auth", false))
                .thenAnswer(invocation -> delayedColdLoad());
        assertCachedLookupsProgress(() -> Player.get(coldUser, "cold-auth"));
        verify(database).loadPlayer(coldUser, "cold-auth", false);
        verifyNoMoreInteractions(database);
    }

    @Test
    void routedCaptainPickAndFollowingQueueTaskCompleteWhileUnrelatedColdLoadIsHeld() throws Exception {
        when(database.loadPlayer("cold-auth")).thenAnswer(invocation -> delayedColdLoad());
        ExecutorService loader = executor(1);
        ExecutorService queue = executor(1);
        AtomicReference<Thread> queueThread = new AtomicReference<>();
        AtomicReference<Thread> pickThread = new AtomicReference<>();
        AtomicReference<Future<?>> routedTask = new AtomicReference<>();
        CountDownLatch queueTaskStarted = new CountDownLatch(1);
        PickupLogic logic = mock(PickupLogic.class);
        String draftToken = "draft-token";
        int generation = 7;
        int pickIndex = 2;
        PickupBot bot = new PickupBot("test", mock(FtwglApi.class), mock(DiscordService.class),
                mock(PermissionService.class), mock(PickupRoleCache.class),
                task -> fail("Captain picks must use the queue executor"),
                task -> routedTask.set(queue.submit(() -> {
                    Thread.currentThread().setName("QueueExec-player-cache-test");
                    queueThread.set(Thread.currentThread());
                    queueTaskStarted.countDown();
                    task.run();
                })),
                task -> fail("Captain routing must use the queue executor, not pickup I/O"),
                task -> fail("Captain routing must use the queue executor, not pick I/O"));
        Field logicField = PickupBot.class.getDeclaredField("logic");
        logicField.setAccessible(true);
        logicField.set(bot, logic);
        DiscordInteraction interaction = mock(DiscordInteraction.class);
        when(interaction.getMessage()).thenReturn(mock(DiscordMessage.class));
        when(interaction.getUser()).thenReturn(warmUser);
        when(interaction.getComponentId())
                .thenReturn(Config.INT_PICK + "_" + draftToken + "_" + generation + "_" + pickIndex);
        doAnswer(invocation -> {
            pickThread.set(Thread.currentThread());
            assertEquals(1L, releaseColdLoad.getCount(), "Captain pick must run before cold load release");
            return null;
        }).when(logic).cmdPick(interaction, warmPlayer, draftToken, generation, pickIndex);

        try {
            Future<Player> cold = loader.submit(() -> Player.get("cold-auth"));
            await(coldLoadEntered, "Cold load did not start");
            bot.recvInteraction(interaction);
            await(queueTaskStarted, "Captain task was not dispatched");
            Future<String> followingTask = queue.submit(() -> {
                assertSame(queueThread.get(), Thread.currentThread());
                assertEquals(1L, releaseColdLoad.getCount(), "Queue marker must run before cold load release");
                return "queue progressed";
            });

            routedTask.get().get(DEADLINE_SECONDS, TimeUnit.SECONDS);
            assertEquals("queue progressed", followingTask.get(DEADLINE_SECONDS, TimeUnit.SECONDS));
            assertSame(queueThread.get(), pickThread.get(), "Captain pick must execute on QueueExec");
            assertColdLoadStillHeld(cold);
            verify(interaction).deferReply();
            verify(logic).cmdPick(interaction, warmPlayer, draftToken, generation, pickIndex);
            verifyNoMoreInteractions(logic);

            releaseColdLoad.countDown();
            assertNull(cold.get(DEADLINE_SECONDS, TimeUnit.SECONDS));
            verify(database).loadPlayer("cold-auth");
            verifyNoMoreInteractions(database);
        } finally {
            releaseColdLoad.countDown();
        }
    }

    private void assertCachedLookupsProgress(Callable<Player> coldLookup) throws Exception {
        ExecutorService workers = executor(4);
        try {
            Future<Player> cold = workers.submit(coldLookup);
            await(coldLoadEntered, "Cold load did not start");
            List<Future<Player>> warmResults = List.of(
                    workers.submit(() -> Player.get("warm-auth")),
                    workers.submit(() -> Player.get(warmUser)),
                    workers.submit(() -> Player.get(warmUser, "warm-auth")));
            for (Future<Player> warm : warmResults) {
                assertSame(warmPlayer, warm.get(DEADLINE_SECONDS, TimeUnit.SECONDS),
                        "Every cached lookup overload must finish before cold load release");
            }
            assertColdLoadStillHeld(cold);

            releaseColdLoad.countDown();
            assertNull(cold.get(DEADLINE_SECONDS, TimeUnit.SECONDS));
        } finally {
            releaseColdLoad.countDown();
        }
    }

    private Player delayedColdLoad() throws InterruptedException {
        assertFalse(Thread.holdsLock(Player.class), "Database load must run outside the global Player monitor");
        coldLoadEntered.countDown();
        assertTrue(releaseColdLoad.await(60, TimeUnit.SECONDS), "Test must release the injected database delay");
        return null;
    }

    private void assertColdLoadStillHeld(Future<Player> cold) {
        assertEquals(1L, releaseColdLoad.getCount(), "Cold load must not be released prematurely");
        assertFalse(cold.isDone(), "Cached lookups and queue work must finish while the cold load is held");
    }

    private ExecutorService executor(int threads) {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        executors.add(executor);
        return executor;
    }

    private static void await(CountDownLatch latch, String message) throws InterruptedException {
        assertTrue(latch.await(DEADLINE_SECONDS, TimeUnit.SECONDS), message);
    }

    private static DiscordUser user(String id) {
        DiscordUser user = mock(DiscordUser.class);
        when(user.getId()).thenReturn(id);
        when(user.getUsername()).thenReturn(id);
        return user;
    }
}
