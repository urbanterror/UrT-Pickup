package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.discord.*;
import de.gost0r.pickupbot.discord.jda.JdaDiscordInteraction;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.component.GenericComponentInteractionCreateEvent;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import de.gost0r.pickupbot.ftwgl.FtwglApi;
import de.gost0r.pickupbot.permission.PermissionService;
import de.gost0r.pickupbot.permission.PickupRoleCache;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.lang.reflect.Field;
import java.sql.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests that !forceadd, !reset, !lock, !unlock, and !remove are correctly
 * routed from both the public channel and the admin channel (including
 * super-admin DM).
 *
 * Uses the same real-SQLite + mocked-externals pattern as PickupLogicTest.
 */
class PickupBotCommandRoutingTest {

    static PickupBot bot;
    static PickupLogic logic;
    static DiscordService discord;
    static PermissionService perms;

    static DiscordChannel pubChannel;
    static DiscordChannel admChannel;
    static DiscordChannel dmChannel;

    static Map<String, Player> players = new LinkedHashMap<>();
    static Map<String, DiscordUser> users = new LinkedHashMap<>();

    @BeforeAll
    static void setup() throws Exception {
        resetPlayerCache();

        // -- Mocks --
        discord = mock(DiscordService.class);
        when(discord.getMe()).thenReturn(mockUser("999", "Bot"));

        FtwglApi ftw = mock(FtwglApi.class);
        when(ftw.hasLauncherOn(any())).thenReturn(true);
        when(ftw.checkIfPingStored(any())).thenReturn(true);
        when(ftw.getPlayerRatings(any(Player.class))).thenReturn(0f);
        when(ftw.getPlayerRatings(anyList())).thenReturn(Map.of());
        when(ftw.getTopPlayerRatings()).thenReturn(Collections.emptyMap());
        when(ftw.requestPingUrl(any())).thenReturn("https://test/ping");

        perms = mock(PermissionService.class);
        PickupRoleCache roleCache = new PickupRoleCache();

        // -- Channels --
        pubChannel = mockChannel("9001", "pickup", false, false);
        admChannel = mockChannel("9002", "admin", false, false);
        dmChannel = mockChannel("9003", "dm", true, false);
        when(discord.getChannelById("9001")).thenReturn(pubChannel);
        when(discord.getChannelById("9002")).thenReturn(admChannel);

        // -- Seed DB --
        File tmp = File.createTempFile("pickuprouting", "");
        tmp.delete();
        tmp.deleteOnExit();
        String envPrefix = tmp.getAbsolutePath();
        new File(envPrefix + ".pickup.db").deleteOnExit();

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + envPrefix + ".pickup.db")) {
            seed(c);
        }

        // -- Wire up bot + logic --
        Executor directExecutor = Runnable::run; // Execute tasks synchronously for testing
        bot = new PickupBot(envPrefix, ftw, discord, perms, roleCache, directExecutor, directExecutor, directExecutor, directExecutor);
        logic = new PickupLogic(bot, ftw, discord, perms, roleCache);
        logic.init();

        // Set private fields that are normally initialized by bot.init()
        Field selfField = PickupBot.class.getDeclaredField("self");
        selfField.setAccessible(true);
        selfField.set(bot, discord.getMe());

        Field logicField = PickupBot.class.getDeclaredField("logic");
        logicField.setAccessible(true);
        logicField.set(bot, logic);

        // Pre-load players
        for (String auth : new String[]{
                "alpha", "bravo", "charlie", "delta", "echo",
                "foxtrot", "golf", "hotel", "india", "juliet"}) {
            Player p = Player.get(auth);
            if (p != null) players.put(auth, p);
        }
    }

    @AfterAll
    static void teardown() {
        if (logic != null && logic.db != null) logic.db.disconnect();
        resetPlayerCache();
    }

    @BeforeEach
    void resetQueues() {
        logic.cmdReset("cur");
        logic.cmdUnlock();
        // Reset permission mocks to default (no rights)
        reset(perms);
    }

    // ========== Stats publishing ==========

    @Test void allTimeStatsButtonShowsRequestedPlayerAndPublishButton() {
        DiscordInteraction interaction = mockInteraction(Config.INT_SEASONSTATS + "_bravo_0", users.get("alpha"));

        bot.recvInteraction(interaction);

        ArgumentCaptor<DiscordEmbed> embed = ArgumentCaptor.forClass(DiscordEmbed.class);
        ArgumentCaptor<ArrayList<DiscordComponent>> components = componentCaptor();
        verify(interaction).respondEphemeral(isNull(), embed.capture(), components.capture());
        assertEquals("All time stats", embed.getValue().getDescription());
        assertTrue(embed.getValue().getTitle().contains("bravo"));
        assertFalse(embed.getValue().getTitle().contains("alpha"));
        assertEquals(users.get("bravo").getAvatarUrl(), embed.getValue().getThumbnail());
        assertFalse(embed.getValue().getFields().isEmpty());
        assertPublishButton(components.getValue());
        verify(interaction).deferReply();
        verify(interaction, never()).deferEdit();
        verify(interaction, never()).publishMessage();
    }

    @Test void seasonSelectionKeepsSeasonalStatsPrivateWithoutPublishButton() {
        DiscordInteraction interaction = mockInteraction(Config.INT_SEASONSELECTED + "_bravo", users.get("alpha"));
        when(interaction.getValues()).thenReturn(List.of("1"));

        bot.recvInteraction(interaction);

        ArgumentCaptor<DiscordEmbed> embed = ArgumentCaptor.forClass(DiscordEmbed.class);
        verify(interaction).respondEphemeral(isNull(), embed.capture());
        assertTrue(embed.getValue().getDescription().startsWith("Season 1 "));
        assertTrue(embed.getValue().getTitle().contains("bravo"));
        verify(interaction, never()).respondEphemeral(any(), any(), any());
        verify(interaction).deferReply();
        verify(interaction, never()).publishMessage();
    }

    @Test void allTimeStatsViaSeasonSelectionAlsoHasPublishButton() {
        DiscordInteraction interaction = mockInteraction(Config.INT_SEASONSELECTED + "_alpha", users.get("alpha"));
        when(interaction.getValues()).thenReturn(List.of("0"));

        bot.recvInteraction(interaction);

        ArgumentCaptor<ArrayList<DiscordComponent>> components = componentCaptor();
        verify(interaction).respondEphemeral(isNull(), argThat(embed ->
                "All time stats".equals(embed.getDescription())), components.capture());
        assertPublishButton(components.getValue());
    }

    @Test void publishClickAcknowledgesOriginalMessageBeforePublishing() {
        DiscordInteraction interaction = mockInteraction(Config.INT_PUBLISHSTATS, users.get("alpha"));

        bot.recvInteraction(interaction);

        var order = inOrder(interaction);
        order.verify(interaction).deferEdit();
        order.verify(interaction).publishMessage();
        verify(interaction, never()).deferReply();
        verify(interaction, never()).respondEphemeral(anyString());
    }

    @Test void unregisteredUserCannotPublishStats() {
        DiscordInteraction interaction = mockInteraction(Config.INT_PUBLISHSTATS, mockUser("9999", "unknown"));

        bot.recvInteraction(interaction);

        verify(interaction).respondEphemeral(Config.user_not_registered);
        verify(interaction, never()).publishMessage();
    }

    @Test void missingTargetPlayerDoesNotCreatePublishButton() {
        DiscordInteraction interaction = mockInteraction(Config.INT_SEASONSTATS + "_missingplayer_0", users.get("alpha"));

        bot.recvInteraction(interaction);

        verify(interaction, never()).respondEphemeral(any(), any(), any());
        verify(interaction, never()).publishMessage();
    }

    @Test void publishClickThroughRealAdapterPostsSnapshotThenDeletesOriginal() {
        GenericComponentInteractionCreateEvent event = mock(
                GenericComponentInteractionCreateEvent.class, RETURNS_DEEP_STUBS);
        when(event.getMember()).thenReturn(null);
        when(event.getUser().getId()).thenReturn("1001");
        when(event.getUser().getEffectiveName()).thenReturn("alpha");
        when(event.getComponentId()).thenReturn(Config.INT_PUBLISHSTATS);
        // The viewed player can differ from the user clicking Publish.
        var snapshot = List.of(new EmbedBuilder().setTitle("bravo")
                .setDescription("All time stats").addField("Wins", "42", true).build());
        when(event.getMessage().getEmbeds()).thenReturn(snapshot);
        var hook = event.getHook();
        MessageCreateAction post = event.getMessageChannel().sendMessageEmbeds(snapshot);
        clearInvocations(event, hook, post, event.getMessageChannel());

        bot.recvInteraction(new JdaDiscordInteraction(event));

        verify(event).deferEdit();
        verify(event, never()).deferReply();
        verify(event.getMessageChannel()).sendMessageEmbeds(same(snapshot));
        verify(hook, never()).sendMessageEmbeds(anyList());
        verify(hook, never()).deleteOriginal();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<Message>> success = ArgumentCaptor.forClass(Consumer.class);
        verify(post).queue(success.capture(), any());
        success.getValue().accept(mock(Message.class));
        verify(hook).deleteOriginal();
        verify(hook.deleteOriginal()).queue();
    }

    private static DiscordInteraction mockInteraction(String componentId, DiscordUser user) {
        DiscordInteraction interaction = mock(DiscordInteraction.class);
        DiscordMessage message = mockMessage("", discord.getMe(), pubChannel);
        when(interaction.getComponentId()).thenReturn(componentId);
        when(interaction.getUser()).thenReturn(user);
        when(interaction.getMessage()).thenReturn(message);
        return interaction;
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<ArrayList<DiscordComponent>> componentCaptor() {
        return ArgumentCaptor.forClass(ArrayList.class);
    }

    private static void assertPublishButton(List<DiscordComponent> components) {
        assertEquals(1, components.size());
        DiscordButton button = assertInstanceOf(DiscordButton.class, components.get(0));
        assertEquals("Publish", button.getLabel());
        assertEquals(Config.INT_PUBLISHSTATS, button.getCustomId());
        assertFalse(button.isDisabled());
    }

    // ========== !reset ==========

    @Test void reset_fromPublicChannel_withAdmin_works() {
        // Add a player to queue first
        logic.cmdAddPlayer(players.get("alpha"), gt("TS"), false);
        // Confirm they are in queue
        assertContains(logic.cmdStatus(), "alpha");

        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(true);
        sendCommand("!reset", users.get("alpha"), pubChannel);

        assertContains(logic.cmdStatus(), "Nobody signed up");
    }

    @Test void reset_fromAdminChannel_works() {
        logic.cmdAddPlayer(players.get("bravo"), gt("TS"), false);
        assertContains(logic.cmdStatus(), "bravo");

        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(true);
        when(perms.hasSuperAdminRights(users.get("alpha"))).thenReturn(false);
        sendCommand("!reset", users.get("alpha"), admChannel);

        assertContains(logic.cmdStatus(), "Nobody signed up");
    }

    @Test void reset_fromSuperAdminDM_works() {
        logic.cmdAddPlayer(players.get("charlie"), gt("TS"), false);
        assertContains(logic.cmdStatus(), "charlie");

        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(true);
        when(perms.hasSuperAdminRights(users.get("alpha"))).thenReturn(true);
        sendCommand("!reset", users.get("alpha"), dmChannel);

        assertContains(logic.cmdStatus(), "Nobody signed up");
    }

    @Test void reset_fromAdminChannel_nonAdmin_ignored() {
        logic.cmdAddPlayer(players.get("delta"), gt("TS"), false);

        when(perms.hasAdminRights(users.get("delta"))).thenReturn(false);
        when(perms.hasSuperAdminRights(users.get("delta"))).thenReturn(false);
        sendCommand("!reset", users.get("delta"), admChannel);

        // Queue should NOT be reset
        assertContains(logic.cmdStatus(), "delta");
    }

    @Test void reset_withGametype_fromAdminChannel_works() {
        logic.cmdAddPlayer(players.get("alpha"), gt("TS"), false);
        logic.cmdAddPlayer(players.get("alpha"), gt("CTF"), false);

        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(true);
        sendCommand("!reset TS", users.get("alpha"), admChannel);

        // CTF queue should still have the player, TS queue should be cleared
        String status = logic.cmdStatus().getMessage();
        org.junit.jupiter.api.Assertions.assertTrue(
                status.contains("CTF"), "CTF queue should still exist");
    }

    // ========== !lock / !unlock ==========

    @Test void lock_fromPublicChannel_withAdmin_works() {
        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(true);
        sendCommand("!lock", users.get("alpha"), pubChannel);

        var r = logic.cmdAddPlayer(players.get("bravo"), gt("TS"), false);
        assertContains(r, "lock");
    }

    @Test void lock_fromAdminChannel_works() {
        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(true);
        sendCommand("!lock", users.get("alpha"), admChannel);

        var r = logic.cmdAddPlayer(players.get("bravo"), gt("TS"), false);
        assertContains(r, "lock");
    }

    @Test void unlock_fromAdminChannel_works() {
        logic.cmdLock();
        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(true);
        sendCommand("!unlock", users.get("alpha"), admChannel);

        var r = logic.cmdAddPlayer(players.get("bravo"), gt("TS"), false);
        // Should NOT be rejected with lock message
        if (r.getMessage() != null) {
            org.junit.jupiter.api.Assertions.assertFalse(
                    r.getMessage().toLowerCase().contains("lock"),
                    "Queue should be unlocked");
        }
    }

    @Test void lock_fromSuperAdminDM_works() {
        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(true);
        when(perms.hasSuperAdminRights(users.get("alpha"))).thenReturn(true);
        sendCommand("!lock", users.get("alpha"), dmChannel);

        var r = logic.cmdAddPlayer(players.get("bravo"), gt("TS"), false);
        assertContains(r, "lock");
    }

    @Test void lock_fromPublicChannel_nonAdmin_ignored() {
        when(perms.hasAdminRights(users.get("delta"))).thenReturn(false);
        sendCommand("!lock", users.get("delta"), pubChannel);

        // Queue should NOT be locked
        var r = logic.cmdAddPlayer(players.get("bravo"), gt("TS"), false);
        if (r.getMessage() != null) {
            org.junit.jupiter.api.Assertions.assertFalse(
                    r.getMessage().toLowerCase().contains("lock"),
                    "Non-admin lock should be ignored");
        }
    }

    // ========== !forceadd ==========

    @Test void forceadd_fromPublicChannel_withAdmin_works() {
        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(true);
        when(discord.getUserFromMention("<@1002>")).thenReturn(users.get("bravo"));
        sendCommand("!forceadd TS <@1002>", users.get("alpha"), pubChannel);

        assertContains(logic.cmdStatus(), "bravo");
    }

    @Test void forceadd_fromAdminChannel_works() {
        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(true);
        when(discord.getUserFromMention("<@1003>")).thenReturn(users.get("charlie"));
        sendCommand("!forceadd TS <@1003>", users.get("alpha"), admChannel);

        assertContains(logic.cmdStatus(), "charlie");
    }

    @Test void forceadd_fromSuperAdminDM_works() {
        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(true);
        when(perms.hasSuperAdminRights(users.get("alpha"))).thenReturn(true);
        when(discord.getUserFromMention("<@1004>")).thenReturn(users.get("delta"));
        sendCommand("!forceadd TS <@1004>", users.get("alpha"), dmChannel);

        assertContains(logic.cmdStatus(), "delta");
    }

    @Test void forceadd_fromPublicChannel_nonAdmin_rejected() {
        when(perms.hasAdminRights(users.get("delta"))).thenReturn(false);
        DiscordMessage msg = mockMessage("!forceadd TS <@1002>", users.get("delta"), pubChannel);
        bot.recvMessage(msg);

        verify(msg).reply(Config.player_not_admin);
    }

    @Test void forceadd_wrongArgs_showsUsage() {
        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(true);
        DiscordMessage msg = mockMessage("!forceadd TS", users.get("alpha"), admChannel);
        bot.recvMessage(msg);

        verify(msg).reply(Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_FORCEADD));
    }

    @Test void forceadd_bypassesLock() {
        logic.cmdLock();
        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(true);
        when(discord.getUserFromMention("<@1005>")).thenReturn(users.get("echo"));
        sendCommand("!forceadd TS <@1005>", users.get("alpha"), admChannel);

        assertContains(logic.cmdStatus(), "echo");
    }

    // ========== !topban ==========

    @Test void topBan_routesFromPublicChannelAndHelpDescribesTheCounts() {
        Database original = logic.db;
        Database leaderboard = mock(Database.class);
        when(leaderboard.getTopBans(10)).thenReturn(List.of(new Database.BanCount("alpha", 4, 1)));
        logic.db = leaderboard;
        try {
            DiscordMessage msg = mockMessage("!topban", users.get("alpha"), pubChannel);
            bot.recvMessage(msg);
            ArgumentCaptor<DiscordEmbed> embed = ArgumentCaptor.forClass(DiscordEmbed.class);
            verify(msg).reply(isNull(), embed.capture());
            org.junit.jupiter.api.Assertions.assertEquals("alpha\n", embed.getValue().getFields().get(1).value());
            org.junit.jupiter.api.Assertions.assertEquals("4 (1)\n", embed.getValue().getFields().get(2).value());

            DiscordMessage invalid = mockMessage("!topban TS", users.get("alpha"), pubChannel);
            bot.recvMessage(invalid);
            verify(invalid).reply(Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_TOP_BAN));

            DiscordMessage help = mockMessage("!help topban", users.get("alpha"), pubChannel);
            bot.recvMessage(help);
            verify(help).reply(Config.help_prefix.replace(".cmd.", Config.USE_CMD_TOP_BAN));
            verify(leaderboard).getTopBans(10);
        } finally {
            logic.db = original;
        }
    }

    // ========== !remove ==========

    @Test void remove_fromPublicChannel_selfRemove() {
        logic.cmdAddPlayer(players.get("alpha"), gt("TS"), false);
        assertContains(logic.cmdStatus(), "alpha");

        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(false);
        sendCommand("!remove", users.get("alpha"), pubChannel);

        String status = logic.cmdStatus().getMessage();
        org.junit.jupiter.api.Assertions.assertFalse(
                status.contains("alpha"), "Player should be removed from queue");
    }

    @Test void removeTsWhilePlayingAimLeavesOnlyTsAndDoesNotRejectTheCommand() throws Exception {
        Player alpha = players.get("alpha");
        logic.cmdAddPlayer(alpha, gt("TS"), false);
        logic.cmdAddPlayer(alpha, gt("CTF"), false);
        Match liveAim = startLiveAim(alpha);
        try {
            DiscordMessage removeAim = mockMessage("!remove AIM", users.get("alpha"), pubChannel);
            bot.recvMessage(removeAim);
            verify(removeAim).reply(Config.player_already_match);
            org.junit.jupiter.api.Assertions.assertNotNull(logic.playerInMatch(gt("TS"), alpha));

            DiscordMessage removeTs = mockMessage("!remove TS", users.get("alpha"), pubChannel);
            bot.recvMessage(removeTs);
            verify(removeTs, never()).reply(anyString());
            org.junit.jupiter.api.Assertions.assertNull(logic.playerInMatch(gt("TS"), alpha));
            org.junit.jupiter.api.Assertions.assertNotNull(logic.playerInMatch(gt("CTF"), alpha));
            org.junit.jupiter.api.Assertions.assertTrue(liveAim.isInMatch(alpha));
            org.junit.jupiter.api.Assertions.assertEquals(MatchState.Live, liveAim.getMatchState());

            DiscordMessage removeTsAgain = mockMessage("!remove TS", users.get("alpha"), pubChannel);
            bot.recvMessage(removeTsAgain);
            verify(removeTsAgain).reply("You are not added to any of those queues.");
            org.junit.jupiter.api.Assertions.assertTrue(liveAim.isInMatch(alpha));
        } finally {
            ongoingMatches().remove(liveAim);
            currentMatches().remove(liveAim.getGametype());
        }
    }

    @Test void removeAllWhilePlayingAimLeavesLiveMatchButClearsEverySignup() throws Exception {
        Player bravo = players.get("bravo");
        logic.cmdAddPlayer(bravo, gt("TS"), false);
        logic.cmdAddPlayer(bravo, gt("CTF"), false);
        Match liveAim = startLiveAim(bravo);
        try {
            DiscordMessage remove = mockMessage("!remove", users.get("bravo"), pubChannel);
            bot.recvMessage(remove);
            verify(remove, never()).reply(anyString());
            org.junit.jupiter.api.Assertions.assertNull(logic.playerInMatch(gt("TS"), bravo));
            org.junit.jupiter.api.Assertions.assertNull(logic.playerInMatch(gt("CTF"), bravo));
            org.junit.jupiter.api.Assertions.assertTrue(liveAim.isInMatch(bravo));
            org.junit.jupiter.api.Assertions.assertEquals(MatchState.Live, liveAim.getMatchState());

            DiscordMessage removeAgain = mockMessage("!remove", users.get("bravo"), pubChannel);
            bot.recvMessage(removeAgain);
            verify(removeAgain).reply(Config.player_already_match);
            org.junit.jupiter.api.Assertions.assertTrue(liveAim.isInMatch(bravo));
        } finally {
            ongoingMatches().remove(liveAim);
            currentMatches().remove(liveAim.getGametype());
        }
    }

    @Test void remove_fromPublicChannel_adminRemovesOther() {
        logic.cmdAddPlayer(players.get("bravo"), gt("TS"), false);
        assertContains(logic.cmdStatus(), "bravo");

        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(true);
        when(discord.getUserFromMention("<@1002>")).thenReturn(users.get("bravo"));
        sendCommand("!remove <@1002>", users.get("alpha"), pubChannel);

        String status = logic.cmdStatus().getMessage();
        org.junit.jupiter.api.Assertions.assertFalse(
                status.contains("bravo"), "Admin should be able to remove another player");
    }

    @Test void remove_fromAdminChannel_removesPlayer() {
        logic.cmdAddPlayer(players.get("charlie"), gt("TS"), false);
        assertContains(logic.cmdStatus(), "charlie");

        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(true);
        when(discord.getUserFromMention("<@1003>")).thenReturn(users.get("charlie"));
        sendCommand("!remove <@1003>", users.get("alpha"), admChannel);

        String status = logic.cmdStatus().getMessage();
        org.junit.jupiter.api.Assertions.assertFalse(
                status.contains("charlie"), "Admin should remove player from admin channel");
    }

    @Test void remove_fromSuperAdminDM_removesPlayer() {
        logic.cmdAddPlayer(players.get("delta"), gt("TS"), false);
        assertContains(logic.cmdStatus(), "delta");

        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(true);
        when(perms.hasSuperAdminRights(users.get("alpha"))).thenReturn(true);
        when(discord.getUserFromMention("<@1004>")).thenReturn(users.get("delta"));
        sendCommand("!remove <@1004>", users.get("alpha"), dmChannel);

        String status = logic.cmdStatus().getMessage();
        org.junit.jupiter.api.Assertions.assertFalse(
                status.contains("delta"), "Super-admin should remove player via DM");
    }

    @Test void remove_fromAdminChannel_specificGametype() {
        logic.cmdAddPlayer(players.get("echo"), gt("TS"), false);
        logic.cmdAddPlayer(players.get("echo"), gt("CTF"), false);

        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(true);
        when(discord.getUserFromMention("<@1005>")).thenReturn(users.get("echo"));
        sendCommand("!remove <@1005> TS", users.get("alpha"), admChannel);

        String status = logic.cmdStatus().getMessage();
        // Should still be in CTF
        org.junit.jupiter.api.Assertions.assertTrue(
                status.contains("echo") && status.contains("CTF"),
                "Player should remain in CTF queue");
    }

    @Test void remove_fromAdminChannel_nonAdmin_ignored() {
        logic.cmdAddPlayer(players.get("foxtrot"), gt("TS"), false);

        when(perms.hasAdminRights(users.get("delta"))).thenReturn(false);
        when(perms.hasSuperAdminRights(users.get("delta"))).thenReturn(false);
        sendCommand("!remove <@1006>", users.get("delta"), admChannel);

        // Player should still be in queue (command ignored for non-admin in admin channel)
        assertContains(logic.cmdStatus(), "foxtrot");
    }

    // ========== !baninfo ==========

    @Test void baninfo_fromPublicChannel_self() {
        DiscordMessage msg = mockMessage("!baninfo", users.get("alpha"), pubChannel);
        bot.recvMessage(msg);

        // Should reply with ban info for self (alpha)
        verify(msg).reply(logic.printBanInfo(players.get("alpha")));
    }

    @Test void baninfo_fromPublicChannel_otherPlayer() {
        when(discord.getUserFromMention("<@1002>")).thenReturn(users.get("bravo"));
        DiscordMessage msg = mockMessage("!baninfo <@1002>", users.get("alpha"), pubChannel);
        bot.recvMessage(msg);

        verify(msg).reply(logic.printBanInfo(players.get("bravo")));
    }

    @Test void baninfo_fromAdminChannel_lookupByMention() {
        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(true);
        when(discord.getUserFromMention("<@1003>")).thenReturn(users.get("charlie"));
        DiscordMessage msg = mockMessage("!baninfo <@1003>", users.get("alpha"), admChannel);
        bot.recvMessage(msg);

        verify(msg).reply(logic.printBanInfo(players.get("charlie"), true));
    }

    @Test void baninfo_fromAdminChannel_lookupByUrtauth() {
        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(true);
        DiscordMessage msg = mockMessage("!baninfo delta", users.get("alpha"), admChannel);
        bot.recvMessage(msg);

        verify(msg).reply(logic.printBanInfo(players.get("delta"), true));
    }

    @Test void baninfo_adminChannelShowsOlderHistoryHiddenInPublicChannel() {
        PlayerBan oldBan = new PlayerBan();
        oldBan.player = players.get("juliet");
        oldBan.startTime = System.currentTimeMillis() - PickupLogic.parseDurationFromString("3M");
        oldBan.endTime = oldBan.startTime + PickupLogic.parseDurationFromString("1d");
        oldBan.reason = PlayerBan.BanReason.NOSHOW;
        players.get("juliet").addBan(oldBan);

        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(true);
        DiscordMessage adminMsg = mockMessage("!baninfo juliet", users.get("alpha"), admChannel);
        bot.recvMessage(adminMsg);
        verify(adminMsg).reply(argThat(reply ->
                reply.contains("Past 6 months") && reply.contains("NOSHOW")));

        DiscordMessage publicMsg = mockMessage("!baninfo juliet", users.get("alpha"), pubChannel);
        bot.recvMessage(publicMsg);
        verify(publicMsg).reply(argThat(reply ->
                reply.contains("**Total bans:** 1") && !reply.contains("NOSHOW")));
    }

    @Test void baninfo_fromSuperAdminDM_works() {
        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(true);
        when(perms.hasSuperAdminRights(users.get("alpha"))).thenReturn(true);
        when(discord.getUserFromMention("<@1005>")).thenReturn(users.get("echo"));
        DiscordMessage msg = mockMessage("!baninfo <@1005>", users.get("alpha"), dmChannel);
        bot.recvMessage(msg);

        verify(msg).reply(logic.printBanInfo(players.get("echo")));
    }

    @Test void baninfo_fromAdminChannel_noArgs_unregistered() {
        // From admin channel with no args and null senderPlayer -> should say user not registered
        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(true);
        DiscordMessage msg = mockMessage("!baninfo", users.get("alpha"), admChannel);
        bot.recvMessage(msg);

        verify(msg).reply(Config.user_not_registered);
    }

    @Test void baninfo_fromAdminChannel_nonAdmin_ignored() {
        when(perms.hasAdminRights(users.get("delta"))).thenReturn(false);
        when(perms.hasSuperAdminRights(users.get("delta"))).thenReturn(false);
        DiscordMessage msg = mockMessage("!baninfo <@1001>", users.get("delta"), admChannel);
        bot.recvMessage(msg);

        // Non-admin in admin channel - command should not execute, no reply
        verify(msg, never()).reply(anyString());
    }

    @Test void baninfo_fromPublicChannel_unregisteredUser() {
        var unknown = mockUser("9999", "unknown");
        DiscordMessage msg = mockMessage("!baninfo", unknown, pubChannel);
        bot.recvMessage(msg);

        verify(msg).reply(Config.user_not_registered);
    }

    @Test void privateJoinVotesForMapAfterAsyncValidation() throws Exception {
        Player alpha = players.get("alpha");
        PrivateGroup group = logic.createPrivateGroup(alpha, gt("TS"));
        PickupBot originalBot = logic.bot;
        List<Runnable> ioTasks = new ArrayList<>();
        PickupBot asyncBot = botWithDelayedIo(ioTasks);

        try {
            DiscordMessage msg = mockMessage("!private ut4_turnpike", users.get("alpha"), pubChannel);
            asyncBot.recvMessage(msg);

            org.junit.jupiter.api.Assertions.assertEquals(1, ioTasks.size());
            org.junit.jupiter.api.Assertions.assertNull(alpha.getVotedMap(group.gt));
            org.junit.jupiter.api.Assertions.assertNull(logic.playerInMatch(group.gt, alpha));

            ioTasks.get(0).run();

            org.junit.jupiter.api.Assertions.assertNotNull(logic.playerInMatch(group.gt, alpha));
            org.junit.jupiter.api.Assertions.assertEquals("ut4_turnpike", alpha.getVotedMap(group.gt).name);
        } finally {
            logic.dissolveGroup(group);
            logic.bot = originalBot;
        }
    }

    @Test void directMapVoteAfterAlreadyQueuedJoinWinsWithoutValidation() throws Exception {
        Player alpha = players.get("alpha");
        Gametype ts = gt("TS");
        logic.cmdAddPlayer(alpha, ts, false);
        PickupBot originalBot = logic.bot;
        List<Runnable> ioTasks = new ArrayList<>();
        PickupBot asyncBot = botWithDelayedIo(ioTasks);
        try {
            asyncBot.recvMessage(mockMessage("!ts ut4_turnpike", users.get("alpha"), pubChannel));
            org.junit.jupiter.api.Assertions.assertEquals(0, ioTasks.size());
            org.junit.jupiter.api.Assertions.assertEquals("ut4_turnpike", alpha.getVotedMap(ts).name);

            asyncBot.recvMessage(mockMessage("!map TS ut4_casa", users.get("alpha"), pubChannel));
            org.junit.jupiter.api.Assertions.assertEquals("ut4_casa", alpha.getVotedMap(ts).name);
            org.junit.jupiter.api.Assertions.assertEquals("ut4_casa", alpha.getVotedMap(ts).name);
        } finally {
            logic.bot = originalBot;
        }
    }

    @Test void alreadyQueuedMapVotesApplyInCommandOrderWithoutValidation() throws Exception {
        Player alpha = players.get("alpha");
        Gametype ts = gt("TS");
        logic.cmdAddPlayer(alpha, ts, false);
        PickupBot originalBot = logic.bot;
        List<Runnable> ioTasks = new ArrayList<>();
        PickupBot asyncBot = botWithDelayedIo(ioTasks);
        try {
            asyncBot.recvMessage(mockMessage("!ts ut4_turnpike", users.get("alpha"), pubChannel));
            asyncBot.recvMessage(mockMessage("!ts ut4_casa", users.get("alpha"), pubChannel));
            org.junit.jupiter.api.Assertions.assertEquals(0, ioTasks.size());

            org.junit.jupiter.api.Assertions.assertEquals("ut4_casa", alpha.getVotedMap(ts).name);
        } finally {
            logic.bot = originalBot;
        }
    }

    @Test void removeTsDuringPendingMultiModeJoinStillJoinsCtf() throws Exception {
        Player alpha = players.get("alpha");
        PickupBot originalBot = logic.bot;
        List<Runnable> ioTasks = new ArrayList<>();
        PickupBot asyncBot = botWithDelayedIo(ioTasks);
        try {
            asyncBot.recvMessage(mockMessage("!add TS CTF", users.get("alpha"), pubChannel));
            asyncBot.recvMessage(mockMessage("!remove TS", users.get("alpha"), pubChannel));
            org.junit.jupiter.api.Assertions.assertEquals(1, ioTasks.size());

            ioTasks.get(0).run();

            org.junit.jupiter.api.Assertions.assertNull(logic.playerInMatch(gt("TS"), alpha));
            org.junit.jupiter.api.Assertions.assertNotNull(logic.playerInMatch(gt("CTF"), alpha));
        } finally {
            logic.bot = originalBot;
        }
    }

    @Test void resetTsDuringPendingMultiModeJoinStillJoinsCtf() throws Exception {
        Player alpha = players.get("alpha");
        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(true);
        PickupBot originalBot = logic.bot;
        List<Runnable> ioTasks = new ArrayList<>();
        PickupBot asyncBot = botWithDelayedIo(ioTasks);
        try {
            asyncBot.recvMessage(mockMessage("!add TS CTF", users.get("alpha"), pubChannel));
            asyncBot.recvMessage(mockMessage("!reset TS", users.get("alpha"), pubChannel));
            org.junit.jupiter.api.Assertions.assertEquals(1, ioTasks.size());

            ioTasks.get(0).run();

            org.junit.jupiter.api.Assertions.assertNull(logic.playerInMatch(gt("TS"), alpha));
            org.junit.jupiter.api.Assertions.assertNotNull(logic.playerInMatch(gt("CTF"), alpha));
        } finally {
            logic.bot = originalBot;
        }
    }

    private PickupBot botWithDelayedIo(List<Runnable> ioTasks) throws Exception {
        Executor direct = Runnable::run;
        PickupBot asyncBot = new PickupBot(bot.env, logic.ftwglApi, discord, perms,
                new PickupRoleCache(), direct, direct, ioTasks::add, direct);
        Field self = PickupBot.class.getDeclaredField("self");
        self.setAccessible(true);
        self.set(asyncBot, discord.getMe());
        Field botLogic = PickupBot.class.getDeclaredField("logic");
        botLogic.setAccessible(true);
        botLogic.set(asyncBot, logic);
        logic.bot = asyncBot;
        return asyncBot;
    }

    // ========== Helpers ==========

    private static Match startLiveAim(Player player) throws Exception {
        Match aim = new Match(logic, new Gametype("AIM", 2, true, false), List.of(), perms);
        aim.addPlayer(player);
        Field state = Match.class.getDeclaredField("state");
        state.setAccessible(true);
        state.set(aim, MatchState.Live);
        currentMatches().put(aim.getGametype(), new Match(logic, aim.getGametype(), List.of(), perms));
        ongoingMatches().add(aim);
        return aim;
    }

    @SuppressWarnings("unchecked")
    private static Map<Gametype, Match> currentMatches() throws Exception {
        Field field = PickupLogic.class.getDeclaredField("curMatch");
        field.setAccessible(true);
        return (Map<Gametype, Match>) field.get(logic);
    }

    @SuppressWarnings("unchecked")
    private static List<Match> ongoingMatches() throws Exception {
        Field field = PickupLogic.class.getDeclaredField("ongoingMatches");
        field.setAccessible(true);
        return (List<Match>) field.get(logic);
    }

    static Gametype gt(String name) { return logic.getGametypeByString(name); }

    static void assertContains(PickupReply r, String sub) {
        org.junit.jupiter.api.Assertions.assertNotNull(r.getMessage(), "Reply message was null");
        org.junit.jupiter.api.Assertions.assertTrue(
                r.getMessage().toLowerCase().contains(sub.toLowerCase()),
                "Expected '" + sub + "' in: " + r.getMessage());
    }

    /** Send a command through the bot's recvMessage. */
    static void sendCommand(String content, DiscordUser user, DiscordChannel channel) {
        DiscordMessage msg = mockMessage(content, user, channel);
        bot.recvMessage(msg);
    }

    static DiscordMessage mockMessage(String content, DiscordUser user, DiscordChannel channel) {
        DiscordMessage msg = mock(DiscordMessage.class);
        when(msg.getContent()).thenReturn(content);
        when(msg.getUser()).thenReturn(user);
        when(msg.getChannel()).thenReturn(channel);
        return msg;
    }

    static DiscordUser mockUser(String id, String name) {
        return new DiscordUser() {
            public String getId() { return id; }
            public String getUsername() { return name; }
            public String getMentionString() { return "<@" + id + ">"; }
            public String getAvatarUrl() { return "https://cdn.test/" + id; }
            public boolean isInGuild(String guildId) { return true; }
            public void sendPrivateMessage(String msg) {}
            public void sendPrivateMessage(String msg, DiscordEmbed e, java.util.List<DiscordComponent> c) {}
            public boolean equals(Object o) { return o instanceof DiscordUser du && id.equals(du.getId()); }
            public int hashCode() { return id.hashCode(); }
        };
    }

    static DiscordChannel mockChannel(String id, String name, boolean isPrivate, boolean isThread) {
        var ch = mock(DiscordChannel.class);
        when(ch.getId()).thenReturn(id);
        when(ch.getName()).thenReturn(name);
        when(ch.isPrivateChannel()).thenReturn(isPrivate);
        when(ch.isThreadChannel()).thenReturn(isThread);
        return ch;
    }

    static void seed(Connection c) throws Exception {
        var s = c.createStatement();
        s.executeUpdate("CREATE TABLE IF NOT EXISTS player (userid TEXT,urtauth TEXT,elo INTEGER DEFAULT 1000,elochange INTEGER DEFAULT 0,active TEXT,country TEXT,enforce_ac TEXT DEFAULT 'true',coins INTEGER DEFAULT 1000,eloboost INTEGER DEFAULT 0,mapvote INTEGER DEFAULT 0,mapban INTEGER DEFAULT 0,proctf TEXT DEFAULT 'true',PRIMARY KEY(userid,urtauth))");
        s.executeUpdate("CREATE TABLE IF NOT EXISTS gametype (gametype TEXT PRIMARY KEY,teamsize INTEGER,active TEXT,recent_map_exclude INTEGER DEFAULT 2)");
        s.executeUpdate("CREATE TABLE IF NOT EXISTS map (map TEXT,gametype TEXT,active TEXT,banned_until INTEGER DEFAULT 0,PRIMARY KEY(map,gametype))");
        s.executeUpdate("CREATE TABLE IF NOT EXISTS banlist (ID INTEGER PRIMARY KEY AUTOINCREMENT,player_userid TEXT,player_urtauth TEXT,reason TEXT,start INTEGER,end INTEGER,pardon TEXT,forgiven BOOLEAN)");
        s.executeUpdate("CREATE TABLE IF NOT EXISTS report (ID INTEGER PRIMARY KEY AUTOINCREMENT,player_userid TEXT,player_urtauth TEXT,reporter_userid TEXT,reporter_urtauth TEXT,reason TEXT,match INTEGER)");
        s.executeUpdate("CREATE TABLE IF NOT EXISTS match (ID INTEGER PRIMARY KEY AUTOINCREMENT,server INTEGER,gametype TEXT,state TEXT,starttime INTEGER,map TEXT,elo_red INTEGER,elo_blue INTEGER,score_red INTEGER DEFAULT 0,score_blue INTEGER DEFAULT 0)");
        s.executeUpdate("CREATE TABLE IF NOT EXISTS player_in_match (ID INTEGER PRIMARY KEY AUTOINCREMENT,matchid INTEGER,player_userid TEXT,player_urtauth TEXT,team TEXT)");
        s.executeUpdate("CREATE TABLE IF NOT EXISTS score (ID INTEGER PRIMARY KEY AUTOINCREMENT,kills INTEGER DEFAULT 0,deaths INTEGER DEFAULT 0,assists INTEGER DEFAULT 0,caps INTEGER DEFAULT 0,returns INTEGER DEFAULT 0,fckills INTEGER DEFAULT 0,stopcaps INTEGER DEFAULT 0,protflag INTEGER DEFAULT 0)");
        s.executeUpdate("CREATE TABLE IF NOT EXISTS stats (pim INTEGER PRIMARY KEY,ip TEXT,status TEXT,score_1 INTEGER,score_2 INTEGER)");
        s.executeUpdate("CREATE TABLE IF NOT EXISTS server (ID INTEGER PRIMARY KEY AUTOINCREMENT,ip TEXT,port INTEGER,rcon TEXT,password TEXT,active TEXT,region TEXT)");
        s.executeUpdate("CREATE TABLE IF NOT EXISTS roles (role TEXT,type TEXT,PRIMARY KEY(role))");
        s.executeUpdate("CREATE TABLE IF NOT EXISTS channels (channel TEXT,type TEXT,PRIMARY KEY(channel,type))");
        s.executeUpdate("CREATE TABLE IF NOT EXISTS season (number INTEGER,startdate INTEGER,enddate INTEGER,PRIMARY KEY(number))");
        s.executeUpdate("CREATE TABLE IF NOT EXISTS bets (ID INTEGER PRIMARY KEY AUTOINCREMENT,player_userid TEXT,player_urtauth TEXT,matchid INTEGER,team INTEGER,won TEXT,amount INTEGER,odds FLOAT)");
        s.executeUpdate("CREATE TABLE IF NOT EXISTS spree (ID INTEGER PRIMARY KEY AUTOINCREMENT,player_userid TEXT,player_urtauth TEXT,gametype TEXT,spree INTEGER DEFAULT 0,personal_best INTEGER DEFAULT 0,personal_worst INTEGER DEFAULT 0)");

        s.executeUpdate("INSERT INTO season VALUES(1,1704067200000,1767225600000)");

        s.executeUpdate("INSERT INTO gametype VALUES('TS',5,'true',2)");
        s.executeUpdate("INSERT INTO gametype VALUES('CTF',5,'true',2)");

        for (var m : new String[]{"ut4_turnpike","ut4_abbey","ut4_casa","ut4_uptown","ut4_algiers"})
            s.executeUpdate("INSERT INTO map VALUES('" + m + "','TS','true',0)");
        for (var m : new String[]{"ut4_riyadh","ut4_sanchez","ut4_tohunga_b8"})
            s.executeUpdate("INSERT INTO map VALUES('" + m + "','CTF','true',0)");

        s.executeUpdate("INSERT INTO server VALUES(1,'192.168.1.1',27960,'rcon1','pw1','true','EU')");
        s.executeUpdate("INSERT INTO server VALUES(2,'192.168.1.2',27960,'rcon2','pw2','true','NA')");

        s.executeUpdate("INSERT INTO channels VALUES('9001','PUBLIC')");
        s.executeUpdate("INSERT INTO channels VALUES('9002','ADMIN')");

        String[][] pp = {
                {"1001","alpha","1200","US"}, {"1002","bravo","1150","DE"},
                {"1003","charlie","1300","FR"}, {"1004","delta","1050","BR"},
                {"1005","echo","950","AU"},  {"1006","foxtrot","1100","GB"},
                {"1007","golf","1250","SE"}, {"1008","hotel","1400","PL"},
                {"1009","india","800","CA"}, {"1010","juliet","1000","IT"},
        };
        var ps = c.prepareStatement("INSERT INTO player(userid,urtauth,elo,elochange,active,country) VALUES(?,?,?,0,'true',?)");
        for (var p : pp) {
            ps.setString(1, p[0]);
            ps.setString(2, p[1]);
            ps.setInt(3, Integer.parseInt(p[2]));
            ps.setString(4, p[3]);
            ps.executeUpdate();
            var u = mockUser(p[0], p[1]);
            when(discord.getUserById(p[0])).thenReturn(u);
            users.put(p[1], u);
        }
        ps.close();
        s.close();
    }

    static void resetPlayerCache() {
        try {
            Field f = Player.class.getDeclaredField("playerList");
            f.setAccessible(true);
            ((List<?>) f.get(null)).clear();
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
