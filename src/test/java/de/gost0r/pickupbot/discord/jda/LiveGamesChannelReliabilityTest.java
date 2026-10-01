package de.gost0r.pickupbot.discord.jda;

import de.gost0r.pickupbot.command.common.CommandInitService;
import de.gost0r.pickupbot.discord.DiscordChannel;
import de.gost0r.pickupbot.pickup.Match;
import de.gost0r.pickupbot.pickup.Gametype;
import de.gost0r.pickupbot.pickup.PickupBot;
import de.gost0r.pickupbot.pickup.PickupChannelType;
import de.gost0r.pickupbot.pickup.PickupLogic;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.managers.channel.concrete.TextChannelManager;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.restaction.ChannelAction;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.requests.restaction.MessageEditAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises the scheduled entry point with the real request budget and a simulated Discord history. */
class LiveGamesChannelReliabilityTest {
    @TempDir Path directory;
    private final JDA jda = mock(JDA.class);
    private final PickupBot bot = mock(PickupBot.class);
    private final PickupLogic logic = mock(PickupLogic.class);
    private final SelfUser user = mock(SelfUser.class);
    private final MutableClock clock = new MutableClock();
    private ManualExecutor worker = new ManualExecutor();
    private final List<GuildFixture> guilds = new ArrayList<>();
    private final List<Match> matches = new ArrayList<>();
    private DiscordRequestBudget budget;
    private LiveGamesChannelService service;

    @BeforeEach
    void setup() {
        budget = new DiscordRequestBudget(directory, clock);
        service = new LiveGamesChannelService(jda, bot, budget, true, clock, worker);
        when(jda.getSelfUser()).thenReturn(user);
        when(user.getId()).thenReturn("100");
        when(jda.getStatus()).thenReturn(JDA.Status.CONNECTED);
        when(bot.getLogic()).thenReturn(logic);
        when(logic.getChannelByType(PickupChannelType.PUBLIC)).thenAnswer(ignored ->
                guilds.stream().map(g -> g.publicChannel).toList());
        when(logic.getPublicLiveMatches()).thenAnswer(ignored -> List.copyOf(matches));
        matches.add(match(42));
    }

    @AfterEach
    void shutdown() { service.shutdown(); }

    @Test
    void readyEventCreatesEmptyChannelBeforeAnyMatchGoesLive() {
        GuildFixture guild = guild("200");
        guild.exists = false;
        matches.clear();
        var forwarder = new JdaForwarder(jda, bot, mock(CommandInitService.class), service);

        forwarder.onReady(mock(ReadyEvent.class));
        verify(bot).init();
        assertEquals(1, worker.tasks.size());
        worker.tasks.removeFirst().run();

        verify(guild.guild).createTextChannel("pickup-live-0");
        verify(guild.create).complete();
        assertTrue(guild.exists);
        assertTrue(guild.history.isEmpty());
        verify(guild.send, never()).complete();
        verify(guild.channel, never()).delete();
    }

    @Test
    void overlappingTicksDuringBlockedRestCallDoNotQueueDuplicateWork() throws Exception {
        GuildFixture guild = guild("200");
        tick(); // recover empty channel
        clock.advance(2_000);
        service.shutdown();
        var executor = Executors.newSingleThreadExecutor();
        service = new LiveGamesChannelService(jda, bot, budget, true, clock, executor);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(ignored -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS), "Test did not release the REST call");
            return guild.completeSend();
        }).when(guild.send).complete();
        try {
            service.tick(); // new service recovers history
            executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
            clock.advance(2_000);
            service.tick();
            assertTrue(entered.await(5, TimeUnit.SECONDS), "REST call never started");
            var callers = Executors.newFixedThreadPool(4);
            try {
                for (int i = 0; i < 100; i++) callers.submit(service::tick);
            } finally {
                callers.shutdown();
                assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS));
            }
            release.countDown();
            executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
            verify(guild.send, times(1)).complete();
            verify(logic, times(3)).getPublicLiveMatches();

            when(matches.getFirst().getMatchInfo()).thenReturn("Updated score");
            clock.advance(30_000);
            service.tick();
            executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
            verify(guild.edit).complete(); // running flag was released after the blocked call
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void sharedBudgetRotatesGuildsAndPublishesInBothWithoutBursting() {
        GuildFixture first = guild("200");
        GuildFixture second = guild("300");
        tick(); // first guild reads history; second must wait
        verify(first.channel.getHistory().retrievePast(100)).complete();
        verify(second.channel.getHistory().retrievePast(100), never()).complete();
        advanceAndTick(2_000); // second guild gets next slot
        verify(second.channel.getHistory().retrievePast(100)).complete();
        advanceAndTick(2_000); // first preview
        verify(first.send).complete();
        verify(second.send, never()).complete();
        advanceAndTick(2_000); // second preview
        verify(second.send).complete();
        assertEquals(1, first.history.size());
        assertEquals(1, second.history.size());
        assertTrue(budget.prometheus().contains("urt_discord_live_operations_total 4\n"));
    }

    @Test
    void refreshUsesRealBudgetAndClockAndSkipsUnchangedPreviews() {
        GuildFixture guild = guild("200");
        publish(guild);
        when(matches.getFirst().getMatchInfo()).thenReturn("Changed score");
        advanceAndTick(29_999);
        verify(guild.edit, never()).complete();
        advanceAndTick(1);
        verify(guild.edit, times(1)).complete();
        advanceAndTick(30_000); // content unchanged
        verify(guild.edit, times(1)).complete();
        assertTrue(budget.prometheus().contains("urt_discord_live_operations_total 3\n"));
    }

    @Test
    void highTrafficSlowsRefreshAnd429PausesThenResumesIt() {
        GuildFixture guild = guild("200");
        publish(guild);
        when(matches.getFirst().getMatchInfo()).thenReturn("Changed score");
        for (int i = 0; i < 300; i++) budget.recordRequest();
        advanceAndTick(30_000);
        verify(guild.edit, never()).complete();
        assertTrue(budget.prometheus().contains("urt_discord_live_refresh_interval_seconds 60\n"));
        budget.recordResponse(429, "0", "45", "45");
        advanceAndTick(30_000); // due, and traffic has expired, but Retry-After still applies
        verify(guild.edit, never()).complete();
        advanceAndTick(15_999);
        verify(guild.edit, never()).complete();
        advanceAndTick(1);
        verify(guild.edit).complete();
    }

    @Test
    void persistedRenameCooldownSurvivesServiceRestartWhilePreviewsContinue() {
        GuildFixture guild = guild("200");
        publish(guild);
        when(guild.channel.getName()).thenReturn("pickup-live-ts2");
        doAnswer(invocation -> {
            String name = invocation.getArgument(0);
            when(guild.channel.getName()).thenReturn(name);
            return guild.manager;
        }).when(guild.manager).setName(anyString());
        advanceAndTick(2_000);
        long renamedAt = clock.millis();
        verify(guild.manager).setName("pickup-live-ts1");
        matches.add(match(43));
        advanceAndTick(2_000);
        verify(guild.send, times(2)).complete(); // previews update while name is still throttled
        assertEquals("pickup-live-ts1", guild.channel.getName());

        service.shutdown();
        worker = new ManualExecutor();
        budget = new DiscordRequestBudget(directory, clock);
        service = new LiveGamesChannelService(jda, bot, budget, true, clock, worker);
        tick(); // recover both previews
        advanceAndTick(renamedAt + 300_000 - clock.millis() - 1);
        verify(guild.manager, never()).setName("pickup-live-ts2");
        verify(guild.send, times(2)).complete();
        advanceAndTick(1);
        verify(guild.manager).setName("pickup-live-ts2");
        assertEquals("pickup-live-ts2", guild.channel.getName());
    }

    @Test
    void successfulRemoteSendWithLocalTimeoutIsRecoveredWithoutDuplicate() {
        GuildFixture guild = guild("200");
        tick();
        doAnswer(ignored -> {
            guild.completeSend(); // Discord accepted the message before the connection timed out
            throw new UncheckedIOException(new SocketTimeoutException("Response lost"));
        }).when(guild.send).complete();
        advanceAndTick(2_000);
        assertEquals(1, guild.history.size());
        advanceAndTick(29_999);
        verify(guild.channel.getHistory().retrievePast(100), times(1)).complete();
        advanceAndTick(1); // failure backoff expires: recover the remotely accepted message
        verify(guild.channel.getHistory().retrievePast(100), times(2)).complete();
        advanceAndTick(2_000);
        verify(guild.send, times(1)).complete();
        assertEquals(1, guild.history.size());
        assertTrue(budget.prometheus().contains("urt_discord_live_errors_total 1\n"));
    }

    @Test
    void unknownMessageOnEditRecreatesPreviewAfterRequestSpacing() {
        GuildFixture guild = guild("200");
        publish(guild);
        guild.history.clear(); // moderator deleted the original message
        when(matches.getFirst().getMatchInfo()).thenReturn("Changed score");
        doThrow(discordError(ErrorResponse.UNKNOWN_MESSAGE)).when(guild.edit).complete();
        advanceAndTick(30_000);
        tick();
        verify(guild.send, times(1)).complete();
        advanceAndTick(2_000);
        verify(guild.send, times(2)).complete();
        assertEquals(1, guild.history.size());
        assertTrue(budget.prometheus().contains("urt_discord_live_errors_total 0\n"));
    }

    @Test
    void alreadyDeletedFinishedMessageDoesNotBlockOtherPreviews() {
        GuildFixture guild = guild("200");
        Message ended = guild.message("old", LiveGamesChannelService.preview(match(41), "200"));
        guild.history.add(ended);
        tick();
        var delete = guild.channel.deleteMessageById("old");
        doThrow(discordError(ErrorResponse.UNKNOWN_MESSAGE)).when(delete).complete();
        advanceAndTick(2_000);
        advanceAndTick(2_000);
        verify(guild.channel.deleteMessageById("old"), times(1)).complete();
        verify(guild.send).complete();
    }

    @Test
    void externallyDeletedChannelIsRecreatedAfterCacheGracePeriod() {
        GuildFixture guild = guild("200");
        publish(guild);
        guild.exists = false;
        guild.history.clear();
        advanceAndTick(31_000);
        verify(guild.create).complete();
        advanceAndTick(2_000);
        verify(guild.send, times(2)).complete();
        assertEquals(1, guild.history.size());
    }

    @Test
    void permissionLossDuringEditRecoversAfterPermissionsAreRestored() {
        GuildFixture guild = guild("200");
        publish(guild);
        when(matches.getFirst().getMatchInfo()).thenReturn("Changed score");
        doThrow(discordError(ErrorResponse.MISSING_PERMISSIONS)).when(guild.edit).complete();
        advanceAndTick(30_000); // permission was revoked after the cache-based preflight check
        guild.canManage = false;
        advanceAndTick(30_000);
        verify(guild.edit, times(1)).complete();
        verify(guild.channel.getHistory().retrievePast(100), times(1)).complete();
        guild.canManage = true;
        doReturn(null).when(guild.edit).complete();
        advanceAndTick(2_000); // recover history
        advanceAndTick(2_000); // update the recovered preview
        verify(guild.edit, times(2)).complete();
        verify(guild.send, times(1)).complete();
    }

    @Test
    void recoveryPaginatesAndRemovesDuplicatesWithoutTouchingUnmanagedMessages() {
        GuildFixture guild = guild("200");
        MessageEmbed embed = LiveGamesChannelService.preview(matches.getFirst(), "200");
        guild.history.add(guild.message("newest", embed));
        for (int i = 1; i < 100; i++) {
            guild.history.add(guild.message("unmanaged-" + i, new net.dv8tion.jda.api.EmbedBuilder()
                    .setDescription("Unmanaged message").build()));
        }
        guild.history.add(guild.message("duplicate", embed));
        Message forged = guild.message("other-user", embed);
        User otherUser = mock(User.class);
        when(otherUser.getId()).thenReturn("999");
        when(forged.getAuthor()).thenReturn(otherUser);
        guild.history.add(forged);
        tick();
        verify(guild.send, never()).complete();
        advanceAndTick(2_000);
        verify(guild.channel).getHistoryBefore("unmanaged-99", 100);
        advanceAndTick(2_000);
        verify(guild.channel.deleteMessageById("duplicate")).complete();
        advanceAndTick(2_000);
        verify(guild.send, never()).complete();
        verify(guild.channel, never()).deleteMessageById("other-user");
        verify(guild.channel, never()).deleteMessageById(startsWith("unmanaged-"));
        verify(guild.channel, never()).deleteMessageById("newest");
    }

    @Test
    void failedRenameCheckpointPreventsDiscordMutation() throws Exception {
        GuildFixture guild = guild("200");
        guild.exists = false;
        Files.createDirectory(directory.resolve("state.properties.tmp")); // deterministic write failure, even as root
        tick();
        verify(guild.create, never()).complete();
        assertFalse(guild.exists);
        Files.delete(directory.resolve("state.properties.tmp"));
        advanceAndTick(30_000);
        verify(guild.create).complete();
        assertFalse(new DiscordRequestBudget(directory, clock).canRename("200"));
    }

    @Test
    void disabledUninitializedAndDisconnectedServiceDoNotSubmitWork() {
        guild("200");
        when(bot.getLogic()).thenReturn(null);
        service.tick();
        assertTrue(worker.tasks.isEmpty());
        when(bot.getLogic()).thenReturn(logic);
        when(jda.getStatus()).thenReturn(JDA.Status.RECONNECT_QUEUED);
        service.tick();
        assertTrue(worker.tasks.isEmpty());
        when(jda.getStatus()).thenReturn(JDA.Status.CONNECTED);
        service = new LiveGamesChannelService(jda, bot, budget, false, clock, worker);
        service.tick();
        assertTrue(worker.tasks.isEmpty());
    }

    @Test
    void shutdownAndSubmissionRaceDoNotThrowOrLeaveWorkQueued() {
        guild("200");
        ManualExecutor stoppingWorker = new ManualExecutor() {
            @Override public void execute(Runnable command) {
                shutdown(); // shutdown races with tick after its initial checks
                super.execute(command);
            }
        };
        service.shutdown();
        service = new LiveGamesChannelService(jda, bot, budget, true, clock, stoppingWorker);
        assertDoesNotThrow(service::tick);
        assertTrue(stoppingWorker.tasks.isEmpty());
        service.shutdown();
        assertDoesNotThrow(service::tick);
        verify(logic, never()).getPublicLiveMatches();
    }

    private void publish(GuildFixture guild) {
        tick();
        advanceAndTick(2_000);
        assertEquals(1, guild.history.size());
    }

    private void advanceAndTick(long millis) {
        clock.advance(millis);
        tick();
    }

    private void tick() {
        service.tick();
        assertEquals(1, worker.tasks.size(), "Expected exactly one scheduled pass");
        worker.tasks.removeFirst().run();
        assertTrue(worker.tasks.isEmpty());
    }

    private GuildFixture guild(String id) {
        GuildFixture fixture = new GuildFixture(id);
        guilds.add(fixture);
        return fixture;
    }

    private static Match match(int id) {
        Match match = mock(Match.class);
        Gametype gametype = mock(Gametype.class);
        when(gametype.getName()).thenReturn("TS");
        when(match.getGametype()).thenReturn(gametype);
        when(match.getID()).thenReturn(id);
        when(match.getMatchInfo()).thenReturn("Match " + id + ": score 3-2");
        match.liveScoreMsgs = new ArrayList<>();
        return match;
    }

    private static ErrorResponseException discordError(ErrorResponse response) {
        ErrorResponseException exception = mock(ErrorResponseException.class);
        when(exception.getErrorResponse()).thenReturn(response);
        return exception;
    }

    private class GuildFixture {
        final Guild guild = mock(Guild.class);
        final SelfMember self = mock(SelfMember.class);
        final Role everyone = mock(Role.class);
        final DiscordChannel publicChannel = mock(DiscordChannel.class);
        final TextChannel channel = mock(TextChannel.class, RETURNS_DEEP_STUBS);
        final TextChannelManager manager = mock(TextChannelManager.class, RETURNS_SELF);
        final MessageCreateAction send = mock(MessageCreateAction.class, RETURNS_SELF);
        final MessageEditAction edit = mock(MessageEditAction.class, RETURNS_SELF);
        @SuppressWarnings("unchecked")
        final ChannelAction<TextChannel> create = mock(ChannelAction.class, RETURNS_SELF);
        final List<Message> history = new ArrayList<>();
        boolean exists = true;
        boolean canManage = true;
        MessageEmbed pendingEmbed;
        String pendingMessageId;
        String pendingContent;
        int sequence;

        GuildFixture(String id) {
            when(jda.getGuildById(id)).thenReturn(guild);
            when(publicChannel.getGuildId()).thenReturn(id);
            when(guild.getId()).thenReturn(id);
            when(guild.getSelfMember()).thenReturn(self);
            when(guild.getPublicRole()).thenReturn(everyone);
            when(self.getIdLong()).thenReturn(100L);
            when(everyone.getIdLong()).thenReturn(Long.parseLong(id));
            when(self.hasPermission(any(Permission[].class))).thenAnswer(ignored -> canManage);
            when(self.hasPermission(eq(channel), any(Permission[].class))).thenAnswer(ignored -> canManage);
            when(self.hasPermission(anyCollection())).thenReturn(true);
            when(self.hasPermission(eq(channel), anyCollection())).thenReturn(true);
            when(guild.getTextChannels()).thenAnswer(ignored -> exists ? List.of(channel) : List.of());
            when(guild.getTextChannelById(id + "0")).thenAnswer(ignored -> exists ? channel : null);
            when(guild.createTextChannel(anyString())).thenReturn(create);
            when(create.complete()).thenAnswer(ignored -> { exists = true; return channel; });
            when(channel.getId()).thenReturn(id + "0");
            when(channel.getName()).thenReturn("pickup-live-ts1");
            when(channel.getTopic()).thenReturn("Live now: TS × 1 | 1 match. Read-only match previews and live scoreboard links.");
            try {
                budget.rememberLiveChannel("100", id, id + "0");
            } catch (java.io.IOException e) {
                throw new UncheckedIOException(e);
            }
            when(manager.setTopic(anyString())).thenAnswer(invocation -> {
                String topic = invocation.getArgument(0);
                when(channel.getTopic()).thenReturn(topic);
                return manager;
            });
            when(channel.getGuild()).thenReturn(guild);
            when(channel.getManager()).thenReturn(manager);
            List<PermissionOverride> overrides = List.of(
                    override(Long.parseLong(id), false, Permission.VIEW_CHANNEL.getRawValue(), LiveGamesChannelService.WRITE_PERMISSIONS),
                    override(100, true, LiveGamesChannelService.BOT_PERMISSIONS, 0));
            when(channel.getPermissionOverrides()).thenReturn(overrides);
            when(channel.getHistory().retrievePast(100).complete()).thenAnswer(ignored ->
                    List.copyOf(history.subList(0, Math.min(100, history.size()))));
            when(channel.getHistoryBefore(anyString(), eq(100))).thenAnswer(invocation -> {
                String before = invocation.getArgument(0);
                int start = 0;
                while (start < history.size() && !history.get(start).getId().equals(before)) start++;
                start = Math.min(start + 1, history.size());
                List<Message> page = List.copyOf(history.subList(start, Math.min(start + 100, history.size())));
                MessageHistory result = mock(MessageHistory.class);
                when(result.getRetrievedHistory()).thenReturn(page);
                MessageHistory.MessageRetrieveAction action = mock(MessageHistory.MessageRetrieveAction.class);
                when(action.complete()).thenReturn(result);
                return action;
            });
            when(channel.sendMessageEmbeds(any(MessageEmbed.class))).thenAnswer(invocation -> {
                pendingEmbed = invocation.getArgument(0);
                return send;
            });
            when(send.complete()).thenAnswer(ignored -> completeSend());
            when(send.setContent(anyString())).thenAnswer(invocation -> {
                pendingContent = invocation.getArgument(0);
                return send;
            });
            when(channel.editMessageEmbedsById(anyString(), any(MessageEmbed.class))).thenAnswer(invocation -> {
                pendingMessageId = invocation.getArgument(0);
                pendingEmbed = invocation.getArgument(1);
                return edit;
            });
            when(edit.complete()).thenAnswer(ignored -> {
                Message message = history.stream().filter(m -> m.getId().equals(pendingMessageId)).findFirst().orElseThrow();
                when(message.getEmbeds()).thenReturn(List.of(pendingEmbed));
                when(message.getContentRaw()).thenReturn(pendingContent);
                return message;
            });
            when(edit.setContent(anyString())).thenAnswer(invocation -> {
                pendingContent = invocation.getArgument(0);
                return edit;
            });
        }

        Message completeSend() {
            Message message = message("sent-" + ++sequence, pendingEmbed);
            when(message.getContentRaw()).thenReturn(pendingContent);
            history.addFirst(message);
            return message;
        }

        Message message(String id, MessageEmbed embed) {
            Message message = mock(Message.class);
            when(message.getId()).thenReturn(id);
            when(message.getAuthor()).thenReturn(user);
            when(message.getEmbeds()).thenReturn(List.of(embed));
            when(message.getContentRaw()).thenReturn("Match 42: score 3-2");
            return message;
        }
    }

    private static PermissionOverride override(long id, boolean member, long allow, long deny) {
        PermissionOverride override = mock(PermissionOverride.class);
        when(override.getIdLong()).thenReturn(id);
        when(override.isMemberOverride()).thenReturn(member);
        when(override.getAllowedRaw()).thenReturn(allow);
        when(override.getDeniedRaw()).thenReturn(deny);
        return override;
    }

    private static class MutableClock extends Clock {
        private volatile long millis = 1_000_000;
        void advance(long amount) { millis += amount; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
    }

    private static class ManualExecutor extends AbstractExecutorService {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        private boolean shutdown;
        @Override public void execute(Runnable command) {
            if (shutdown) throw new RejectedExecutionException();
            tasks.addLast(command);
        }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() {
            shutdown();
            List<Runnable> pending = List.copyOf(tasks);
            tasks.clear();
            return pending;
        }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && tasks.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    }
}
