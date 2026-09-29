package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.discord.DiscordInteraction;
import de.gost0r.pickupbot.discord.DiscordChannel;
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
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
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
    void concurrentCommandsForSameColdUserShareOneDatabaseLoad() throws Exception {
        Player hydrated = Player.detached(coldUser, "cold-auth");
        when(database.loadPlayer(coldUser)).thenAnswer(invocation -> {
            delayedColdLoad();
            return hydrated;
        });
        ExecutorService workers = executor(2);
        try {
            Future<Player> first = workers.submit(() -> Player.get(coldUser));
            await(coldLoadEntered, "First load did not start");
            Future<Player> second = workers.submit(() -> Player.get(coldUser));
            assertEquals(1L, releaseColdLoad.getCount());
            verify(database, times(1)).loadPlayer(coldUser);
            releaseColdLoad.countDown();
            assertSame(hydrated, first.get(DEADLINE_SECONDS, TimeUnit.SECONDS));
            assertSame(hydrated, second.get(DEADLINE_SECONDS, TimeUnit.SECONDS));
            verify(database, times(1)).loadPlayer(coldUser);
        } finally {
            releaseColdLoad.countDown();
        }
    }

    @Test
    void newlyRegisteredPlayerLoadsStatsOnceAndRefreshesOnSeasonChange() {
        Season current = new Season(11, 0, 1000);
        Season next = new Season(12, 1000, 2000);
        PlayerStats currentStats = new PlayerStats();
        PlayerStats updatedRank = new PlayerStats();
        PlayerStats nextStats = new PlayerStats();
        when(database.getPlayerStats(warmPlayer, current)).thenReturn(currentStats, updatedRank);
        when(database.getPlayerStats(warmPlayer, next)).thenReturn(nextStats);

        assertSame(currentStats, warmPlayer.getCurrentSeasonStats(database, current));
        assertSame(currentStats, warmPlayer.getCurrentSeasonStats(database, current));
        verify(database, times(1)).getPlayerStats(warmPlayer, current);
        Player.invalidateSeasonStats(); // Another player's scored match can change our rank.
        assertSame(updatedRank, warmPlayer.getCurrentSeasonStats(database, current));
        assertSame(updatedRank, warmPlayer.getCurrentSeasonStats(database, current));
        verify(database, times(2)).getPlayerStats(warmPlayer, current);
        assertSame(nextStats, warmPlayer.getCurrentSeasonStats(database, next));
        verify(database, times(1)).getPlayerStats(warmPlayer, next);
    }

    @Test
    void surrenderRefreshesParticipantsAndInvalidatesOtherPlayersRanks() throws Exception {
        PickupLogic logic = mock(PickupLogic.class);
        PickupBot bot = mock(PickupBot.class);
        AtomicReference<Runnable> backgroundRefresh = new AtomicReference<>();
        Field ioField = PickupBot.class.getDeclaredField("pickupIoExecutor");
        ioField.setAccessible(true);
        ioField.set(bot, (Executor) backgroundRefresh::set);
        logic.bot = bot;
        logic.db = database;
        Season season = new Season(11, 0, 1000);
        logic.currentSeason = season;
        PlayerStats before = new PlayerStats();
        PlayerStats after = new PlayerStats();
        PlayerStats otherBefore = new PlayerStats();
        PlayerStats otherAfter = new PlayerStats();
        DiscordUser otherUser = user("another-player");
        when(warmUser.getMentionString()).thenReturn("<@warm-user>");
        Player other = new Player(otherUser, "another-auth");
        warmPlayer.setCurrentSeasonStats(before, season, Player.currentSeasonStatsRevision());
        other.setCurrentSeasonStats(otherBefore, season, Player.currentSeasonStatsRevision());
        when(database.getPlayerStats(warmPlayer, season)).thenReturn(after);
        when(database.getPlayerStats(other, season)).thenReturn(otherAfter);

        Match match = new Match(logic, new Gametype("TS", 5, true, false), List.of(), mock(PermissionService.class));
        Field statsField = Match.class.getDeclaredField("playerStats");
        statsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Player, MatchStats> stats = (Map<Player, MatchStats>) statsField.get(match);
        stats.put(warmPlayer, new MatchStats());
        Field teamsField = Match.class.getDeclaredField("teamList");
        teamsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, List<Player>> teams = (Map<String, List<Player>>) teamsField.get(match);
        teams.get("red").add(warmPlayer);
        Field surrenderField = Match.class.getDeclaredField("surrender");
        surrenderField.setAccessible(true);
        surrenderField.set(match, new int[]{0, 4});
        Field mapField = Match.class.getDeclaredField("map");
        mapField.setAccessible(true);
        mapField.set(match, new GameMap("ut4_turnpike"));

        match.checkSurrender();

        assertNotNull(backgroundRefresh.get());
        verify(database, never()).getPlayerStats(warmPlayer, season);
        backgroundRefresh.get().run();
        assertSame(after, warmPlayer.getCurrentSeasonStats(database, season));
        assertSame(otherAfter, other.getCurrentSeasonStats(database, season));
        var order = inOrder(database);
        order.verify(database).saveMatch(match);
        order.verify(database).getPlayerStats(warmPlayer, season);
        verify(database, times(1)).getPlayerStats(warmPlayer, season);
        verify(database, times(1)).getPlayerStats(other, season);
    }

    @Test
    void cachedPlayersQueueWithoutWaitingForCommandWorkers() throws Exception {
        PickupLogic logic = mock(PickupLogic.class);
        DiscordChannel channel = mock(DiscordChannel.class);
        when(channel.getName()).thenReturn("pickup");
        when(logic.getChannelByType(PickupChannelType.PUBLIC)).thenReturn(List.of(channel));
        when(logic.cmdRemovePlayer(warmPlayer, null)).thenReturn(PickupReply.NONE);
        AtomicReference<Runnable> queued = new AtomicReference<>();
        PickupBot bot = new PickupBot("test", mock(FtwglApi.class), mock(DiscordService.class),
                mock(PermissionService.class), mock(PickupRoleCache.class),
                task -> fail("Cached queue command must not need a command worker"), queued::set,
                Runnable::run, Runnable::run);
        Field logicField = PickupBot.class.getDeclaredField("logic");
        logicField.setAccessible(true);
        logicField.set(bot, logic);
        Field selfField = PickupBot.class.getDeclaredField("self");
        selfField.setAccessible(true);
        selfField.set(bot, user("bot"));

        bot.recvMessage(message("!remove", warmUser, channel));

        assertNotNull(queued.get());
        queued.get().run();
        verify(logic).cmdRemovePlayer(warmPlayer, null);
    }

    @Test
    void evictedCachedPlayerFallsBackToAsyncLookupWithoutBlockingTheQueue() throws Exception {
        when(database.loadPlayer(warmUser)).thenAnswer(invocation -> delayedColdLoad());
        ExecutorService lookupWorker = executor(1);
        LinkedBlockingQueue<Runnable> queued = new LinkedBlockingQueue<>();
        PickupLogic logic = mock(PickupLogic.class);
        DiscordChannel channel = mock(DiscordChannel.class);
        when(channel.getName()).thenReturn("pickup");
        when(logic.getChannelByType(PickupChannelType.PUBLIC)).thenReturn(List.of(channel));
        PickupBot bot = new PickupBot("test", mock(FtwglApi.class), mock(DiscordService.class),
                mock(PermissionService.class), mock(PickupRoleCache.class),
                lookupWorker, queued::add, Runnable::run, Runnable::run);
        Field logicField = PickupBot.class.getDeclaredField("logic");
        logicField.setAccessible(true);
        logicField.set(bot, logic);
        Field selfField = PickupBot.class.getDeclaredField("self");
        selfField.setAccessible(true);
        selfField.set(bot, user("bot"));
        DiscordMessage join = message("!ts", warmUser, channel);

        try {
            bot.recvMessage(join);
            Runnable cachedDispatch = queued.poll(DEADLINE_SECONDS, TimeUnit.SECONDS);
            assertNotNull(cachedDispatch);
            Player.remove(warmPlayer); // An admin removed them before their queued task ran.
            cachedDispatch.run();
            await(coldLoadEntered, "Evicted player did not start an off-queue lookup");
            assertTrue(queued.isEmpty(), "The queue worker should not wait for the database");
            releaseColdLoad.countDown();
            Runnable resumed = queued.poll(DEADLINE_SECONDS, TimeUnit.SECONDS);
            assertNotNull(resumed);
            resumed.run();
            verify(join).reply(Config.user_not_registered);
        } finally {
            releaseColdLoad.countDown();
        }
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
                task -> fail("Cached captain picks must not need a command worker"),
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

    @Test
    void coldQueueCommandDoesNotHoldUpAnotherPlayersRemoval() throws Exception {
        when(database.loadPlayer(coldUser)).thenAnswer(invocation -> delayedColdLoad());
        ExecutorService lookupWorkers = executor(2);
        ExecutorService queueWorker = executor(1);
        PickupLogic logic = mock(PickupLogic.class);
        DiscordChannel channel = mock(DiscordChannel.class);
        when(channel.getName()).thenReturn("pickup");
        when(logic.getChannelByType(PickupChannelType.PUBLIC)).thenReturn(List.of(channel));
        when(logic.cmdRemovePlayer(warmPlayer, null)).thenReturn(new PickupReply("removed"));
        PickupBot bot = routingBot(logic, lookupWorkers, queueWorker);
        DiscordMessage coldJoin = message("!ts", coldUser, channel);
        DiscordMessage warmRemove = message("!remove", warmUser, channel);
        CountDownLatch coldReplied = new CountDownLatch(1);
        doAnswer(invocation -> {
            coldReplied.countDown();
            return null;
        }).when(coldJoin).reply(Config.user_not_registered);
        CountDownLatch removed = new CountDownLatch(1);
        doAnswer(invocation -> {
            removed.countDown();
            return null;
        }).when(warmRemove).reply("removed");

        try {
            bot.recvMessage(coldJoin);
            await(coldLoadEntered, "Cold queue lookup did not start");
            bot.recvMessage(warmRemove);
            await(removed, "Another player's removal was blocked by the cold lookup");
            assertEquals(1L, releaseColdLoad.getCount());
            verify(logic).cmdRemovePlayer(warmPlayer, null);
            releaseColdLoad.countDown();
            await(coldReplied, "The held cold command did not finish");
        } finally {
            releaseColdLoad.countDown();
        }
    }

    @Test
    void samePlayersRemovalWaitsForEarlierColdJoin() throws Exception {
        when(database.loadPlayer(coldUser)).thenAnswer(invocation -> {
            assertNull(delayedColdLoad());
            return new Player(coldUser, "cold-auth");
        });
        ExecutorService lookupWorkers = executor(2);
        ExecutorService queueWorker = executor(1);
        PickupLogic logic = mock(PickupLogic.class);
        DiscordChannel channel = mock(DiscordChannel.class);
        when(channel.getName()).thenReturn("pickup");
        when(logic.getChannelByType(PickupChannelType.PUBLIC)).thenReturn(List.of(channel));
        Gametype ts = mock(Gametype.class);
        when(logic.getGametypeByString("TS")).thenReturn(ts);
        PickupBot bot = routingBot(logic, lookupWorkers, queueWorker);
        DiscordMessage join = message("!ts", coldUser, channel);
        DiscordMessage remove = message("!remove", coldUser, channel);
        CountDownLatch removed = new CountDownLatch(1);
        when(logic.cmdRemovePlayer(any(Player.class), isNull())).thenAnswer(invocation -> {
            removed.countDown();
            return PickupReply.NONE;
        });

        try {
            bot.recvMessage(join);
            await(coldLoadEntered, "First lookup did not start");
            bot.recvMessage(remove);
            verify(database, times(1)).loadPlayer(coldUser);
            verify(logic, never()).cmdRemovePlayer(any(Player.class), isNull());
            releaseColdLoad.countDown();
            await(removed, "Removal was not routed after the lookup completed");
            verify(database, times(1)).loadPlayer(coldUser);
        } finally {
            releaseColdLoad.countDown();
        }
    }

    private PickupBot routingBot(PickupLogic logic, ExecutorService lookupWorkers, ExecutorService queueWorker) throws Exception {
        PickupBot bot = new PickupBot("test", mock(FtwglApi.class), mock(DiscordService.class),
                mock(PermissionService.class), mock(PickupRoleCache.class),
                lookupWorkers, queueWorker, Runnable::run, Runnable::run);
        Field logicField = PickupBot.class.getDeclaredField("logic");
        logicField.setAccessible(true);
        logicField.set(bot, logic);
        Field selfField = PickupBot.class.getDeclaredField("self");
        selfField.setAccessible(true);
        selfField.set(bot, user("bot"));
        return bot;
    }

    private static DiscordMessage message(String text, DiscordUser sender, DiscordChannel channel) {
        DiscordMessage message = mock(DiscordMessage.class);
        when(message.getContent()).thenReturn(text);
        when(message.getUser()).thenReturn(sender);
        when(message.getChannel()).thenReturn(channel);
        return message;
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
