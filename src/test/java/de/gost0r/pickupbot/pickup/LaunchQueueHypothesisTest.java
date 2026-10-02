package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.discord.DiscordChannel;
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

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * H2 characterization: synchronous launch holds QueueExec while FTW is pending.
 * The routed join API is an asynchronous control: validation leaves QueueExec available
 * and returns to it for the player addition once FTW completes.
 * All external services are mocked; no bot/logic init or production database is used.
 */
class LaunchQueueHypothesisTest {
    private final ExecutorService queue = Executors.newSingleThreadExecutor();
    private final ExecutorService commands = Executors.newSingleThreadExecutor();
    private final ExecutorService pickupIo = Executors.newSingleThreadExecutor();
    private final ExecutorService pickIo = Executors.newSingleThreadExecutor();
    private final CountDownLatch releaseFtw = new CountDownLatch(1);

    private final FtwglApi ftw = mock(FtwglApi.class);
    private final DiscordService discord = mock(DiscordService.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final PickupRoleCache roles = mock(PickupRoleCache.class);
    private PickupBot bot;
    private PickupLogic logic;
    private Player player;
    private DiscordUser user;
    private DiscordChannel channel;
    private Object originalPlayers;
    private Database originalDatabase;
    private PickupLogic originalLogic;
    private boolean savedPlayerState;

    @BeforeEach
    void setUp() throws Exception {
        synchronized (Player.class) {
            originalPlayers = field(Player.class, "playerList").get(null);
            originalDatabase = Player.db;
            originalLogic = Player.logic;
            savedPlayerState = true;
            field(Player.class, "playerList").set(null, new ArrayList<Player>());
            Player.db = mock(Database.class);
            Player.logic = null;
        }

        user = mock(DiscordUser.class);
        when(user.getId()).thenReturn("launch-hypothesis-player");
        when(user.getUsername()).thenReturn("participant");
        when(user.getMentionString()).thenReturn("<@launch-hypothesis-player>");
        player = new Player(user, "launch-hypothesis-auth");
        channel = mock(DiscordChannel.class);
        when(channel.getName()).thenReturn("hypothesis-pickup");

        bot = new PickupBot("test", ftw, discord, permissions, roles, commands, queue, pickupIo, pickIo);
        logic = new PickupLogic(bot, ftw, discord, permissions, roles);
        logic.db = mock(Database.class);
        set(logic, "curMatch", new HashMap<Gametype, Match>());
        set(logic, "ongoingMatches", new ArrayList<Match>());
        set(logic, "activeTeams", new ArrayList<Team>());
        set(logic, "teamsQueued", new HashMap<Team, Gametype>());
        set(logic, "awaitingServer", new ArrayDeque<Match>());
        set(logic, "serverList", new ArrayList<>());
        set(logic, "mapList", new ArrayList<GameMap>());
        set(logic, "channels", Map.of(PickupChannelType.PUBLIC, List.of(channel)));
        set(logic, "dynamicServers", true);
        Player.logic = logic;
        wireBot();
    }

    @AfterEach
    void tearDown() throws Exception {
        // Release even after an assertion fails, then drain workers before restoring Player state.
        releaseFtw.countDown();
        try {
            stop(commands);
            stop(pickupIo);
            stop(pickIo);
            stop(queue);
        } finally {
            commands.shutdownNow();
            pickupIo.shutdownNow();
            pickIo.shutdownNow();
            queue.shutdownNow();
            if (savedPlayerState) {
                synchronized (Player.class) {
                    field(Player.class, "playerList").set(null, originalPlayers);
                    Player.db = originalDatabase;
                    Player.logic = originalLogic;
                }
            }
        }
    }

    @Test
    void participantLaunchRoutedThroughBotBlocksQueueButStatusWorkerStillReplies() throws Exception {
        Match ongoing = mock(Match.class);
        when(ongoing.getID()).thenReturn(42);
        when(ongoing.getPlayerList()).thenReturn(List.of(player));
        set(logic, "ongoingMatches", new ArrayList<>(List.of(ongoing)));

        Thread queueThread = queue.submit(Thread::currentThread).get(5, TimeUnit.SECONDS);
        CountDownLatch ftwEntered = new CountDownLatch(1);
        AtomicReference<Thread> ftwThread = new AtomicReference<>();
        when(ftw.launchAC(player, "192.0.2.42:27960", "test-password")).thenAnswer(call -> {
            ftwThread.set(Thread.currentThread());
            ftwEntered.countDown();
            releaseFtw.await();
            return "mock launch completed";
        });
        DiscordInteraction launch = interaction(
                Config.INT_LAUNCHAC + "_42_192.0.2.42:27960_test-password");

        try {
            bot.recvInteraction(launch);
            assertTrue(ftwEntered.await(5, TimeUnit.SECONDS), "The authorized launch must reach FTW");
            verify(launch).deferReply();
            assertSame(queueThread, ftwThread.get(), "Real interaction routing runs launch on QueueExec");

            Future<Thread> queuedMarker = queue.submit(Thread::currentThread);
            DiscordMessage status = message(Config.CMD_STATUS);
            CountDownLatch statusReplied = new CountDownLatch(1);
            AtomicReference<Thread> statusThread = new AtomicReference<>();
            doAnswer(call -> {
                statusThread.set(Thread.currentThread());
                statusReplied.countDown();
                return null;
            }).when(status).reply(Config.pkup_match_unavi);

            bot.recvMessage(status);
            assertTrue(statusReplied.await(5, TimeUnit.SECONDS),
                    "A real read-only command must reply while launch is still pending");
            assertNotSame(queueThread, statusThread.get());
            assertEquals(1L, releaseFtw.getCount());
            assertThrows(TimeoutException.class, () -> queuedMarker.get(150, TimeUnit.MILLISECONDS),
                    "Work behind the launch cannot run until FTW is released");
            verify(launch, never()).respondEphemeral(anyString());
            verify(permissions, never()).hasAdminRights(user);
            verify(permissions, never()).hasStreamerRights(user);

            releaseFtw.countDown();
            assertSame(queueThread, queuedMarker.get(5, TimeUnit.SECONDS));
            verify(ftw).launchAC(player, "192.0.2.42:27960", "test-password");
            verify(launch).respondEphemeral("mock launch completed");
            verify(status).reply(Config.pkup_match_unavi);
        } finally {
            releaseFtw.countDown();
        }
    }

    @Test
    void queueAddPlayerLeavesQueueAvailableDuringFtwValidationAndAddsPlayerBackOnQueue() throws Exception {
        Gametype gametype = new Gametype("TS", 5, true, false);
        Match signup = mock(Match.class);
        when(signup.getGametype()).thenReturn(gametype);
        when(signup.getMatchState()).thenReturn(MatchState.Signup);
        set(logic, "curMatch", new HashMap<>(Map.of(gametype, signup)));

        CountDownLatch ftwEntered = new CountDownLatch(1);
        AtomicReference<Thread> ftwThread = new AtomicReference<>();
        when(ftw.checkIfPingStored(player)).thenAnswer(call -> {
            ftwThread.set(Thread.currentThread());
            ftwEntered.countDown();
            releaseFtw.await();
            return true;
        });
        when(ftw.hasLauncherOn(player)).thenReturn(true);
        Thread queueThread = queue.submit(Thread::currentThread).get(5, TimeUnit.SECONDS);
        Thread pickupIoThread = pickupIo.submit(Thread::currentThread).get(5, TimeUnit.SECONDS);
        CompletableFuture<PickupReply> completedReply = new CompletableFuture<>();
        AtomicReference<Thread> additionThread = new AtomicReference<>();
        AtomicReference<Thread> replyThread = new AtomicReference<>();
        doAnswer(call -> {
            additionThread.set(Thread.currentThread());
            return null;
        }).when(signup).addPlayer(player);
        // Invoke the same asynchronous API used by the bot's routed join commands.
        Future<?> dispatch = queue.submit(() -> logic.queueAddPlayer(player, List.of(gametype), false, reply -> {
            replyThread.set(Thread.currentThread());
            completedReply.complete(reply);
        }));

        try {
            assertTrue(ftwEntered.await(5, TimeUnit.SECONDS));
            assertSame(pickupIoThread, ftwThread.get(), "The FTW request runs on PickupIO");
            assertNotSame(queueThread, ftwThread.get());
            dispatch.get(5, TimeUnit.SECONDS);
            Future<Thread> queuedMarker = queue.submit(Thread::currentThread);
            assertSame(queueThread, queuedMarker.get(5, TimeUnit.SECONDS),
                    "QueueExec must process subsequent work while FTW is still held");
            DiscordMessage status = message(Config.CMD_STATUS);
            String expectedStatus = logic.cmdStatus().getMessage();
            CountDownLatch statusReplied = new CountDownLatch(1);
            AtomicReference<Thread> statusThread = new AtomicReference<>();
            doAnswer(call -> {
                statusThread.set(Thread.currentThread());
                statusReplied.countDown();
                return null;
            }).when(status).reply(expectedStatus);

            bot.recvMessage(status);
            assertTrue(statusReplied.await(5, TimeUnit.SECONDS),
                    "An independent command can reply while add-player waits for FTW");
            assertNotSame(queueThread, statusThread.get());
            assertEquals(1L, releaseFtw.getCount());
            assertFalse(completedReply.isDone(), "Join completion must still wait for FTW validation");
            verify(signup, never()).addPlayer(any());

            releaseFtw.countDown();
            assertSame(PickupReply.NONE, completedReply.get(5, TimeUnit.SECONDS));
            assertSame(queueThread, additionThread.get());
            assertSame(queueThread, replyThread.get());
            verify(signup).addPlayer(player);
            verify(ftw).checkIfPingStored(player);
            verify(ftw).hasLauncherOn(player);
            verify(status).reply(expectedStatus);
        } finally {
            releaseFtw.countDown();
            // Teardown drains PickupIO before QueueExec and restores Player state last.
        }
    }

    private void wireBot() throws Exception {
        set(bot, "logic", logic);
        DiscordUser self = mock(DiscordUser.class);
        when(self.getId()).thenReturn("hypothesis-bot");
        set(bot, "self", self);
    }

    private DiscordMessage message(String content) {
        DiscordMessage message = mock(DiscordMessage.class);
        when(message.getContent()).thenReturn(content);
        when(message.getUser()).thenReturn(user);
        when(message.getChannel()).thenReturn(channel);
        return message;
    }

    private DiscordInteraction interaction(String componentId) {
        DiscordMessage launchMessage = message("launch button");
        DiscordInteraction interaction = mock(DiscordInteraction.class);
        when(interaction.getComponentId()).thenReturn(componentId);
        when(interaction.getUser()).thenReturn(user);
        when(interaction.getMessage()).thenReturn(launchMessage);
        return interaction;
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        field(target.getClass(), name).set(target, value);
    }

    private static void stop(ExecutorService executor) throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Test worker did not terminate");
        }
    }
}
