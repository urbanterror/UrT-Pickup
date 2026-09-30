package de.gost0r.pickupbot.discord.jda;

import de.gost0r.pickupbot.discord.DiscordChannel;
import de.gost0r.pickupbot.discord.DiscordMessage;
import de.gost0r.pickupbot.pickup.*;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.managers.channel.concrete.TextChannelManager;
import net.dv8tion.jda.api.requests.restaction.ChannelAction;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.requests.restaction.MessageEditAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LiveGamesChannelServiceTest {
    private final JDA jda = mock(JDA.class);
    private final PickupLogic logic = mock(PickupLogic.class);
    private final DiscordRequestBudget budget = mock(DiscordRequestBudget.class);
    private final Guild guild = mock(Guild.class);
    private final SelfMember self = mock(SelfMember.class);
    private final SelfUser user = mock(SelfUser.class);
    private final Role everyone = mock(Role.class);
    private final TextChannel channel = mock(TextChannel.class, RETURNS_DEEP_STUBS);
    private final TextChannelManager manager = mock(TextChannelManager.class, RETURNS_SELF);
    private LiveGamesChannelService service;

    @BeforeEach
    void setup() {
        service = new LiveGamesChannelService(jda, mock(PickupBot.class), budget, true);
        when(jda.getSelfUser()).thenReturn(user);
        when(user.getId()).thenReturn("100");
        when(guild.getId()).thenReturn("200");
        when(guild.getSelfMember()).thenReturn(self);
        when(guild.getPublicRole()).thenReturn(everyone);
        when(self.getIdLong()).thenReturn(100L);
        when(everyone.getIdLong()).thenReturn(200L);
        when(self.hasPermission(any(Permission[].class))).thenReturn(true);
        when(self.hasPermission(anyCollection())).thenReturn(true);
        when(self.hasPermission(eq(channel), any(Permission[].class))).thenReturn(true);
        when(self.hasPermission(eq(channel), anyCollection())).thenReturn(true);
        when(budget.tryAcquire()).thenReturn(true);
        when(channel.getId()).thenReturn("300");
        when(channel.getName()).thenReturn("live-games-1");
        when(channel.getGuild()).thenReturn(guild);
        when(channel.getManager()).thenReturn(manager);
        when(guild.getTextChannelById("300")).thenReturn(channel);
        List<PermissionOverride> overrides = List.of(
                override(200, false, Permission.VIEW_CHANNEL.getRawValue(), LiveGamesChannelService.WRITE_PERMISSIONS),
                override(100, true, LiveGamesChannelService.BOT_PERMISSIONS, 0));
        when(channel.getPermissionOverrides()).thenReturn(overrides);
    }

    @AfterEach
    void shutdown() { service.shutdown(); }

    @Test
    @SuppressWarnings("unchecked")
    void createsReadOnlyChannelUpdatesChangedPreviewsAndKeepsChannelWhenEmpty() throws Exception {
        Match match = match(42, "TS - ut4_casa — LIVE (3-2)");
        ChannelAction<TextChannel> create = mock(ChannelAction.class, RETURNS_SELF);
        when(guild.createTextChannel("live-games-1")).thenReturn(create);
        when(create.complete()).thenReturn(channel);
        MessageCreateAction send = mock(MessageCreateAction.class, RETURNS_SELF);
        Message message = mock(Message.class);
        when(message.getId()).thenReturn("400");
        when(send.complete()).thenReturn(message);
        when(channel.sendMessageEmbeds(any(MessageEmbed.class))).thenReturn(send);
        MessageEditAction edit = mock(MessageEditAction.class, RETURNS_SELF);
        when(channel.editMessageEmbedsById(eq("400"), any(MessageEmbed.class))).thenReturn(edit);

        service.reconcile(logic, guild, List.of(match), 0);
        verify(create).addPermissionOverride(everyone, Permission.VIEW_CHANNEL.getRawValue(), LiveGamesChannelService.WRITE_PERMISSIONS);
        verify(create).addPermissionOverride(self, LiveGamesChannelService.BOT_PERMISSIONS, 0);
        verify(budget).reserveRename("200");

        service.reconcile(logic, guild, List.of(match), 0);
        service.reconcile(logic, guild, List.of(match), 0);
        verify(send, times(1)).complete();
        verify(send).setContent("TS - ut4_casa — LIVE (3-2)");
        verify(edit, never()).complete();
        when(match.getMatchInfo()).thenReturn("TS - ut4_casa — LIVE (4-2)");
        service.reconcile(logic, guild, List.of(match), 0);
        verify(edit).complete();
        verify(edit).setContent("TS - ut4_casa — LIVE (4-2)");
        verify(edit).setAllowedMentions(List.of());
        service.reconcile(logic, guild, List.of(), 0);
        verify(channel.deleteMessageById("400")).complete();
        when(budget.canRename("200")).thenReturn(true);
        service.reconcile(logic, guild, List.of(), 0);
        verify(manager).setName("live-games-0");
        verify(channel, never()).delete();
        when(channel.getName()).thenReturn("live-games-0");
        when(budget.canRename("200")).thenReturn(false);
        service.reconcile(logic, guild, List.of(match), 0);
        verify(send, times(2)).complete();
        verify(guild, times(1)).createTextChannel(anyString());
    }

    @Test
    void reusesOwnedChannelAndRecoversPreviewAfterRestart() throws Exception {
        Match match = match(42, "TS - ut4_casa — LIVE (3-2)");
        existingChannel();
        Message previous = mock(Message.class);
        when(previous.getId()).thenReturn("400");
        when(previous.getAuthor()).thenReturn(user);
        MessageEmbed embed = LiveGamesChannelService.preview(match, "200");
        when(previous.getEmbeds()).thenReturn(List.of(embed));
        when(previous.getContentRaw()).thenReturn("TS - ut4_casa — LIVE (3-2)");
        when(channel.getHistory().retrievePast(100).complete()).thenReturn(List.of(previous));
        service.reconcile(logic, guild, List.of(match), 0);
        service.reconcile(logic, guild, List.of(match), 0);
        verify(guild, never()).createTextChannel(anyString());
        verify(channel, never()).sendMessageEmbeds(any(MessageEmbed.class));
        verify(channel, never()).editMessageEmbedsById(anyString(), any(MessageEmbed.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void createsEmptyChannelWithoutTakingOverUserCreatedChannel() throws Exception {
        when(guild.getTextChannels()).thenReturn(List.of(channel));
        when(channel.getTopic()).thenReturn("A user-created live-games-1 channel");
        ChannelAction<TextChannel> create = mock(ChannelAction.class, RETURNS_SELF);
        when(guild.createTextChannel("live-games-0")).thenReturn(create);
        TextChannel created = mock(TextChannel.class);
        when(create.complete()).thenReturn(created);
        service.reconcile(logic, guild, List.of(), 0);
        verify(create).setTopic("urt-pickup:live-games:100:200");
        verify(create).complete();
        verify(channel, never()).delete();
        verify(channel, never()).getManager();
    }

    @Test
    void reusesEmptyOwnedChannelAfterRestartWithoutDeletingIt() throws Exception {
        existingChannel();
        when(channel.getName()).thenReturn("live-games-0");
        service.reconcile(logic, guild, List.of(), 0);
        service.reconcile(logic, guild, List.of(), 0);
        verify(guild, never()).createTextChannel(anyString());
        verify(channel, never()).delete();
        verify(channel, never()).sendMessageEmbeds(any(MessageEmbed.class));
    }

    @Test
    void missingManagePermissionsWarnsAdminsWithoutCreatingAnUnrestrictedChannel() throws Exception {
        when(self.hasPermission(any(Permission[].class))).thenReturn(false);
        DiscordChannel admin = mock(DiscordChannel.class);
        when(admin.getId()).thenReturn("300");
        when(logic.getChannelByType(PickupChannelType.ADMIN)).thenReturn(List.of(admin));
        when(jda.getTextChannelById("300")).thenReturn(channel);
        MessageCreateAction warning = mock(MessageCreateAction.class, RETURNS_SELF);
        when(channel.sendMessage(anyString())).thenReturn(warning);
        service.reconcile(logic, guild, List.of(match(42, "live")), 0);
        service.reconcile(logic, guild, List.of(match(42, "live")), 0);
        verify(guild, never()).createTextChannel(anyString());
        verify(channel, times(1)).sendMessage(contains("Manage Channels and Manage Permissions"));
        verify(warning, times(1)).complete();
    }

    @Test
    void repairsRoleAndMemberAllowsThatWouldBypassEveryoneDeny() {
        List<PermissionOverride> overrides = new ArrayList<>(channel.getPermissionOverrides());
        overrides.add(override(500, false, Permission.MESSAGE_SEND.getRawValue() | Permission.VIEW_CHANNEL.getRawValue(), 0));
        overrides.add(override(600, true, Permission.CREATE_PUBLIC_THREADS.getRawValue(), 0));
        when(channel.getPermissionOverrides()).thenReturn(overrides);
        assertTrue(service.repairPermissions(channel));
        verify(manager).putRolePermissionOverride(500L, Permission.VIEW_CHANNEL.getRawValue(), 0);
        verify(manager).putMemberPermissionOverride(600L, 0, 0);
        verify(manager).complete();
    }

    @Test
    void exhaustedBudgetDoesNotQueueRestWork() throws Exception {
        when(budget.tryAcquire()).thenReturn(false);
        service.reconcile(logic, guild, List.of(match(42, "live")), 0);
        verify(guild, never()).createTextChannel(anyString());
        verify(budget, never()).reserveRename(anyString());
    }

    @Test
    void countChangesWaitForRenameBudgetWhileFinishedPreviewsAreRemoved() throws Exception {
        existingChannel();
        when(channel.getName()).thenReturn("live-games-2");
        Match live = match(42, "live");
        Message previous = mock(Message.class);
        when(previous.getId()).thenReturn("401");
        when(previous.getAuthor()).thenReturn(user);
        MessageEmbed endedEmbed = LiveGamesChannelService.preview(match(41, "finished"), "200");
        when(previous.getEmbeds()).thenReturn(List.of(endedEmbed));
        when(channel.getHistory().retrievePast(100).complete()).thenReturn(List.of(previous));
        service.reconcile(logic, guild, List.of(live), 30_000); // recover history
        service.reconcile(logic, guild, List.of(live), 30_000); // delete ended match
        verify(channel.deleteMessageById("401")).complete();
        verify(channel, never()).delete();
        verify(manager, never()).setName(anyString());

        when(budget.tryAcquire()).thenReturn(false);
        service.reconcile(logic, guild, List.of(live), 30_000);
        verify(manager, never()).setName(anyString());
        when(budget.canRename("200")).thenReturn(true);
        when(budget.tryAcquire()).thenReturn(true);
        service.reconcile(logic, guild, List.of(live), 30_000);
        verify(budget).reserveRename("200");
        verify(manager).setName("live-games-1");
        verify(manager).complete();
    }

    @Test
    void liveSnapshotExcludesPrivateAwaitingAndFinishedMatches() {
        PickupLogic actual = new PickupLogic(null, null, null, null, null);
        List<Match> matches = new ArrayList<>();
        for (MatchState state : List.of(MatchState.Live, MatchState.AwaitingServer, MatchState.Done)) {
            for (boolean isPrivate : List.of(false, true)) {
                Match match = match(matches.size() + 1, "preview");
                Gametype gametype = mock(Gametype.class);
                when(gametype.getPrivate()).thenReturn(isPrivate);
                when(match.getGametype()).thenReturn(gametype);
                when(match.getMatchState()).thenReturn(state);
                matches.add(match);
            }
        }
        org.springframework.test.util.ReflectionTestUtils.setField(actual, "ongoingMatches", matches);
        assertEquals(List.of(matches.getFirst()), actual.getPublicLiveMatches());
    }

    @Test
    void previewLinksOnlyToScoreboardInItsOwnGuild() {
        Match match = match(42, "Map and teams");
        DiscordMessage scoreboard = mock(DiscordMessage.class);
        DiscordChannel thread = mock(DiscordChannel.class);
        when(scoreboard.getChannel()).thenReturn(thread);
        when(thread.getGuildId()).thenReturn("200");
        when(thread.getId()).thenReturn("123");
        when(scoreboard.getId()).thenReturn("456");
        match.liveScoreMsgs.add(scoreboard);
        assertEquals("[Live scoreboard](https://discord.com/channels/200/123/456)",
                LiveGamesChannelService.preview(match, "200").getDescription());
        assertEquals("Live scoreboard is not available yet.",
                LiveGamesChannelService.preview(match, "999").getDescription());
    }

    @Test
    void previewUsesLiveCommandGtvInfoWithoutRepeatingMatchSummary() {
        Match match = match(42, "**TS #42**: **[**ut4_casa**] [**LIVE (3-2)**] [**red**]** VS **[**blue**]**");
        when(match.getGtvServer()).thenReturn(mock(de.gost0r.pickupbot.pickup.server.Server.class));

        MessageEmbed embed = LiveGamesChannelService.preview(match, "200");

        assertEquals("Live scoreboard is not available yet.\n" + Config.pkup_go_pub_calm, embed.getDescription());
        assertNull(embed.getTitle());
        assertEquals("urt-pickup-live:42", embed.getFooter().getText());
    }

    private void existingChannel() {
        when(guild.getTextChannels()).thenReturn(List.of(channel));
        when(channel.getTopic()).thenReturn("urt-pickup:live-games:100:200");
    }

    private static Match match(int id, String info) {
        Match match = mock(Match.class);
        when(match.getID()).thenReturn(id);
        when(match.getMatchInfo()).thenReturn(info);
        match.liveScoreMsgs = new ArrayList<>();
        return match;
    }

    private static PermissionOverride override(long id, boolean member, long allow, long deny) {
        PermissionOverride override = mock(PermissionOverride.class);
        when(override.getIdLong()).thenReturn(id);
        when(override.isMemberOverride()).thenReturn(member);
        when(override.getAllowedRaw()).thenReturn(allow);
        when(override.getDeniedRaw()).thenReturn(deny);
        return override;
    }
}
