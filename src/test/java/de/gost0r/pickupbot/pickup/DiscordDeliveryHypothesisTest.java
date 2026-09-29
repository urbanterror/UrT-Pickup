package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.discord.DiscordChannel;
import de.gost0r.pickupbot.discord.DiscordEmbed;
import de.gost0r.pickupbot.discord.DiscordMessage;
import de.gost0r.pickupbot.discord.DiscordService;
import de.gost0r.pickupbot.discord.DiscordUser;
import de.gost0r.pickupbot.discord.jda.JdaDiscordMessage;
import de.gost0r.pickupbot.discord.jda.JdaDiscordService;
import de.gost0r.pickupbot.ftwgl.FtwglApi;
import de.gost0r.pickupbot.permission.PermissionService;
import de.gost0r.pickupbot.permission.PickupRoleCache;
import de.gost0r.pickupbot.pickup.server.Server;
import de.gost0r.pickupbot.pickup.server.ServerMonitor;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.requests.restaction.CacheRestAction;
import net.dv8tion.jda.api.requests.restaction.MessageEditAction;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Characterizes application submission boundaries; no Discord quota or delivery is simulated. */
class DiscordDeliveryHypothesisTest {

    private Database previousDatabase;
    private Object previousPlayerCache;
    private Database database;

    @BeforeEach
    void isolatePlayerCache() throws Exception {
        synchronized (Player.class) {
            previousDatabase = Player.db;
            previousPlayerCache = playerCacheField().get(null);
            playerCacheField().set(null, new ArrayList<Player>());
            database = mock(Database.class);
            Player.db = database;
        }
    }

    @AfterEach
    void restorePlayerCache() throws Exception {
        synchronized (Player.class) {
            Player.db = previousDatabase;
            playerCacheField().set(null, previousPlayerCache);
        }
    }

    @Test
    void actualJdaServiceBlocksOnRestLookupEvenWhenMemberIsCached() throws Exception {
        JDA jda = mock(JDA.class);
        Guild guild = mock(Guild.class);
        Member cachedMember = mock(Member.class);
        @SuppressWarnings("unchecked")
        CacheRestAction<Member> retrieval = mock(CacheRestAction.class);
        when(jda.getGuildById("117622053061787657")).thenReturn(guild);
        when(guild.getMemberById("123")).thenReturn(cachedMember);
        when(guild.retrieveMemberById("123")).thenReturn(retrieval);
        CountDownLatch restLookupEntered = new CountDownLatch(1);
        CountDownLatch releaseRestLookup = new CountDownLatch(1);
        when(retrieval.complete()).thenAnswer(invocation -> {
            restLookupEntered.countDown();
            assertTrue(releaseRestLookup.await(5, SECONDS));
            return cachedMember;
        });
        JdaDiscordService service = new JdaDiscordService(jda);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<DiscordUser> lookup = executor.submit(() -> service.getUserById("123"));
            assertTrue(restLookupEntered.await(5, SECONDS));
            assertThrows(TimeoutException.class, () -> lookup.get(150, java.util.concurrent.TimeUnit.MILLISECONDS),
                    "The caller waits on Discord REST despite the member already being cached");
            verify(guild, never()).getMemberById("123");

            releaseRestLookup.countDown();
            DiscordUser resolved = lookup.get(5, SECONDS);
            assertSame(cachedMember, ((de.gost0r.pickupbot.discord.jda.JdaDiscordUser) resolved).getMember());
            verify(retrieval).complete();
        } finally {
            releaseRestLookup.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, SECONDS));
        }
    }

    @Test
    void blockedDiscordLookupDoesNotDelayQueueAnnouncementsOrFollowingQueueWork() throws Exception {
        DiscordService service = mock(DiscordService.class);
        DiscordChannel mentionChannel = mock(DiscordChannel.class);
        DiscordChannel controlChannel = mock(DiscordChannel.class);
        DiscordUser user = cachedUser();
        when(mentionChannel.getGuildId()).thenReturn("guild");
        CountDownLatch lookupEntered = new CountDownLatch(1);
        CountDownLatch releaseLookup = new CountDownLatch(1);
        when(service.getUserById("123")).thenAnswer(invocation -> {
            lookupEntered.countDown();
            assertTrue(releaseLookup.await(5, SECONDS), "Test must release blocked lookup");
            return user;
        });
        ExecutorService queueExecutor = Executors.newSingleThreadExecutor();
        ExecutorService independentExecutor = Executors.newSingleThreadExecutor();
        PickupBot bot = bot(service, queueExecutor);

        try {
            Future<?> blockedLookup = independentExecutor.submit(() -> service.getUserById("123"));
            assertTrue(lookupEntered.await(5, SECONDS));
            Future<?> mentionSend = queueExecutor.submit(
                    () -> bot.sendMsg(List.of(mentionChannel), "Ready <@123>"));
            Future<?> followingQueueTask = queueExecutor.submit(() -> { });

            queueExecutor.submit(() -> bot.sendMsg(List.of(controlChannel), "Queue status"))
                    .get(5, SECONDS);
            mentionSend.get(5, SECONDS);
            followingQueueTask.get(5, SECONDS);
            verify(controlChannel).sendMessage("Queue status");
            verify(mentionChannel).sendMessage("Ready <@123>");
            verifyNoInteractions(database);
            assertFalse(blockedLookup.isDone());

            releaseLookup.countDown();
            blockedLookup.get(5, SECONDS);
            verify(service, times(1)).getUserById("123");
            verifyNoInteractions(database);
        } finally {
            releaseLookup.countDown();
            queueExecutor.shutdownNow();
            independentExecutor.shutdownNow();
            assertTrue(queueExecutor.awaitTermination(5, SECONDS));
            assertTrue(independentExecutor.awaitTermination(5, SECONDS));
        }
    }

    @Test
    void cachedPlayerFormatsCrossGuildMentionWithoutDiscordOrDatabaseLookup() {
        DiscordService service = mock(DiscordService.class);
        DiscordChannel channel = mock(DiscordChannel.class);
        DiscordUser user = cachedUser();
        when(channel.getGuildId()).thenReturn("other-guild");

        PickupBot bot = bot(service, Runnable::run);
        DiscordEmbed embed = new DiscordEmbed();
        bot.sendMsg(List.of(channel), "Ready <@123>");
        bot.sendMsg(List.of(channel), "Ready <@123>", embed);
        bot.sendMsgToEdit(List.of(channel), "Ready <@123>", embed, List.of());

        verifyNoInteractions(service);
        verify(user, times(3)).isInGuild("other-guild");
        verify(channel).sendMessage("Ready **cached-auth**");
        verify(channel).sendMessage("Ready **cached-auth**", embed);
        verify(channel).sendMessage("Ready **cached-auth**", embed, List.of());
        verifyNoInteractions(database);
    }

    @Test
    void allSendVariantsPreserveUnknownMentionsWithoutLoadingPlayers() {
        DiscordService service = mock(DiscordService.class);
        DiscordChannel channel = mock(DiscordChannel.class);
        PickupBot bot = bot(service, Runnable::run);
        DiscordEmbed embed = new DiscordEmbed();
        String text = "Ready <@999> <@!998> <@&997>";

        bot.sendMsg(List.of(channel), text);
        bot.sendMsg(List.of(channel), text, embed);
        bot.sendMsgToEdit(List.of(channel), text, embed, List.of());

        verify(channel).sendMessage(text);
        verify(channel).sendMessage(text, embed);
        verify(channel).sendMessage(text, embed, List.of());
        verifyNoInteractions(service, database);
    }

    @Test
    void everyLiveScoreUpdateSubmitsAnEditToEveryMessageIncludingUnchangedScores() throws Exception {
        PickupLogic logic = mock(PickupLogic.class);
        Gametype gametype = mock(Gametype.class);
        when(gametype.getTeamSize()).thenReturn(5);
        Server server = mock(Server.class);
        ServerMonitor monitor = mock(ServerMonitor.class);
        when(server.getServerMonitor()).thenReturn(monitor);
        when(monitor.getScoreArray()).thenReturn(new int[]{1, 0}, new int[]{1, 0}, new int[]{2, 0});
        Match match = new Match(logic, gametype, List.of(), mock(PermissionService.class));
        setField(match, "state", MatchState.Live);
        setField(match, "server", server);
        DiscordMessage first = mock(DiscordMessage.class);
        DiscordMessage second = mock(DiscordMessage.class);
        match.liveScoreMsgs.addAll(List.of(first, second));

        match.updateScoreEmbed();
        match.updateScoreEmbed();
        match.updateScoreEmbed();

        ArgumentCaptor<DiscordEmbed> firstEdits = ArgumentCaptor.forClass(DiscordEmbed.class);
        ArgumentCaptor<DiscordEmbed> secondEdits = ArgumentCaptor.forClass(DiscordEmbed.class);
        verify(first, times(3)).edit(firstEdits.capture());
        verify(second, times(3)).edit(secondEdits.capture());
        verify(monitor, times(3)).getScoreArray();
        List<DiscordEmbed> snapshots = firstEdits.getAllValues();
        assertEquals(snapshots, secondEdits.getAllValues());
        assertEquals(snapshots.get(0), snapshots.get(1), "Identical snapshots are submitted again");
        assertTrue(snapshots.get(0).getFields().get(0).name().contains("1\n"));
        assertTrue(snapshots.get(2).getFields().get(0).name().contains("2\n"));
        assertEquals(2, match.getScoreRed());
    }

    @Test
    void jdaEditQueuesEverySnapshotWithoutWaitingForDelivery() throws Exception {
        Message message = mock(Message.class);
        MessageEditAction action = mock(MessageEditAction.class);
        when(message.editMessage(any(MessageEditData.class))).thenReturn(action);
        JdaDiscordMessage adapter = new JdaDiscordMessage(message);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            // queue() is a mocked submission boundary: no HTTP request or completion occurs.
            Future<?> caller = executor.submit(() -> {
                for (int score = 1; score <= 3; score++) {
                    DiscordEmbed embed = new DiscordEmbed();
                    embed.setTitle("Score " + score);
                    adapter.edit(embed);
                }
            });
            caller.get(5, SECONDS);

            ArgumentCaptor<MessageEditData> edits = ArgumentCaptor.forClass(MessageEditData.class);
            verify(message, times(3)).editMessage(edits.capture());
            verify(action, times(3)).queue();
            verify(action, never()).complete();
            assertEquals(List.of("Score 1", "Score 2", "Score 3"), edits.getAllValues().stream()
                    .map(edit -> edit.getEmbeds().get(0).getTitle()).toList());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, SECONDS));
        }
    }

    private DiscordUser cachedUser() {
        DiscordUser user = mock(DiscordUser.class);
        when(user.getId()).thenReturn("123");
        when(user.isInGuild("guild")).thenReturn(true);
        Player player = new Player(user, "cached-auth");
        assertSame(player, Player.get(user));
        return user;
    }

    private static PickupBot bot(DiscordService service, java.util.concurrent.Executor queueExecutor) {
        return new PickupBot("test", mock(FtwglApi.class), service, mock(PermissionService.class),
                mock(PickupRoleCache.class), Runnable::run, queueExecutor, Runnable::run, Runnable::run);
    }

    private static Field playerCacheField() throws Exception {
        Field field = Player.class.getDeclaredField("playerList");
        field.setAccessible(true);
        return field;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
