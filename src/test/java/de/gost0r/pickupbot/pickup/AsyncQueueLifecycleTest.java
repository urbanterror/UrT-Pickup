package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.discord.DiscordInteraction;
import de.gost0r.pickupbot.discord.DiscordChannel;
import de.gost0r.pickupbot.discord.DiscordMessage;
import de.gost0r.pickupbot.discord.DiscordService;
import de.gost0r.pickupbot.discord.DiscordUser;
import de.gost0r.pickupbot.ftwgl.FtwglApi;
import de.gost0r.pickupbot.permission.PermissionService;
import de.gost0r.pickupbot.permission.PickupRoleCache;
import de.gost0r.pickupbot.pickup.server.Server;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AsyncQueueLifecycleTest {
    private final ManualExecutor queue = new ManualExecutor();
    private final ManualExecutor io = new ManualExecutor();
    private final ManualExecutor pickIo = new ManualExecutor();
    private FtwglApi ftw;
    private PickupLogic logic;
    private Player player;
    private Match match;
    private Gametype gametype;
    private Map<Gametype, Match> current;

    @BeforeEach
    void setUp() throws Exception {
        ftw = mock(FtwglApi.class);
        PickupBot bot = new PickupBot("test", ftw, mock(DiscordService.class),
                mock(PermissionService.class), mock(PickupRoleCache.class), Runnable::run, queue, io, pickIo);
        logic = new PickupLogic(bot, ftw, mock(DiscordService.class),
                mock(PermissionService.class), mock(PickupRoleCache.class));
        gametype = new Gametype("TS", 5, true, false);
        match = mock(Match.class);
        when(match.getGametype()).thenReturn(gametype);
        when(match.getMatchState()).thenReturn(MatchState.Signup);
        current = new HashMap<>();
        current.put(gametype, match);
        set(logic, "curMatch", current);
        set(logic, "ongoingMatches", new ArrayList<Match>());
        set(logic, "activeTeams", new ArrayList<Team>());
        set(logic, "teamsQueued", new HashMap<Team, Gametype>());
        set(logic, "awaitingServer", new ArrayDeque<Match>());
        set(logic, "serverList", new ArrayList<Server>());
        set(logic, "mapList", new ArrayList<GameMap>());
        set(logic, "channels", new HashMap<PickupChannelType, List<de.gost0r.pickupbot.discord.DiscordChannel>>());
        set(logic, "dynamicServers", true);
        logic.db = mock(Database.class);

        DiscordUser user = mock(DiscordUser.class);
        when(user.getId()).thenReturn("123");
        player = mock(Player.class);
        when(player.getDiscordUser()).thenReturn(user);
        when(player.getUrtauth()).thenReturn("alpha");
        when(player.getEnforceAC()).thenReturn(true);
        when(ftw.checkIfPingStored(player)).thenReturn(true);
        when(ftw.hasLauncherOn(player)).thenReturn(true);
    }

    @Test
    void slowJoinDoesNotBlockOtherQueueWorkOrCaptainPick() {
        List<PickupReply> replies = new ArrayList<>();
        logic.queueAddPlayer(player, List.of(gametype), false, replies::add);
        DiscordInteraction interaction = mock(DiscordInteraction.class);
        queue.execute(() -> logic.cmdPick(interaction, player, "expired-draft", 1, 0));

        queue.runNext();
        verify(interaction).respondEphemeral("This pick is no longer available. Use the latest buttons.");
        verifyNoInteractions(ftw);
        verify(match, never()).addPlayer(any());

        io.runNext();
        queue.runNext();
        verify(match).addPlayer(player);
        assertEquals(1, replies.size());
    }

    @Test
    void removeOrResetWhileValidationRunsCannotReaddPlayer() {
        logic.queueAddPlayer(player, List.of(gametype), false, reply -> {});
        logic.cmdRemovePlayer(player, null);
        io.runNext();
        queue.runNext();
        verify(match, never()).addPlayer(any());

        logic.queueAddPlayer(player, List.of(gametype), false, reply -> {});
        logic.cmdReset("cur", "TS");
        io.runNext();
        queue.runNext();
        verify(match, never()).addPlayer(any());
    }

    @Test
    void joinValidatedDuringNormalMatchRolloverJoinsTheNewSignupQueue() {
        List<PickupReply> replies = new ArrayList<>();
        when(player.getDiscordUser().getMentionString()).thenReturn("alpha");
        logic.queueAddPlayer(player, List.of(gametype), false, replies::add);
        logic.matchStarted(match); // the old queue filled while validation was pending
        Match nextSignup = current.get(gametype);

        io.runNext();
        queue.runNext();

        verify(match, never()).addPlayer(any());
        assertTrue(nextSignup.isInMatch(player));
        assertEquals(1, replies.size());
    }

    @Test
    void removingOneModeDoesNotCancelPendingJoinToAnotherMode() {
        Gametype ctf = new Gametype("CTF", 5, true, false);
        Match ctfMatch = mock(Match.class);
        when(ctfMatch.getMatchState()).thenReturn(MatchState.Signup);
        current.put(ctf, ctfMatch);
        logic.queueAddPlayer(player, List.of(gametype, ctf), false, reply -> {});
        logic.cmdRemovePlayer(player, List.of(gametype));

        io.runNext();
        queue.runNext();

        verify(match, never()).addPlayer(any());
        verify(ctfMatch).addPlayer(player);
    }

    @Test
    void resettingOneModeDoesNotCancelPendingJoinToAnotherMode() {
        Gametype ctf = new Gametype("CTF", 5, true, false);
        Match ctfMatch = mock(Match.class);
        when(ctfMatch.getMatchState()).thenReturn(MatchState.Signup);
        current.put(ctf, ctfMatch);
        logic.queueAddPlayer(player, List.of(gametype, ctf), false, reply -> {});
        logic.cmdReset("TS");

        io.runNext();
        queue.runNext();

        verify(match, never()).addPlayer(any());
        verify(ctfMatch).addPlayer(player);
    }

    @Test
    void overlappingJoinsCanBothCompleteAndValidateOncePerRequest() {
        Gametype ctf = new Gametype("CTF", 5, true, false);
        Match second = mock(Match.class);
        when(second.getMatchState()).thenReturn(MatchState.Signup);
        current.put(ctf, second);
        logic.queueAddPlayer(player, List.of(gametype), false, reply -> {});
        logic.queueAddPlayer(player, List.of(gametype, ctf), false, reply -> {});
        io.runNext();
        queue.runNext();
        verify(match).addPlayer(player);
        io.runNext();
        queue.runNext();
        verify(match, times(2)).addPlayer(player);
        verify(second).addPlayer(player);
        verify(ftw, times(2)).checkIfPingStored(player);
        verify(ftw, times(2)).hasLauncherOn(player);
    }

    @Test
    void lockAppliedWhileValidationRunsRejectsTheJoin() {
        List<PickupReply> replies = new ArrayList<>();
        logic.queueAddPlayer(player, List.of(gametype), false, replies::add);
        logic.cmdLock();

        io.runNext();
        queue.runNext();
        verify(match, never()).addPlayer(any());
        assertEquals(Config.pkup_lock, replies.get(0).getMessage());
    }

    @Test
    void failedPingCheckDoesNotAddPlayerAndSendsPingInstructions() {
        when(ftw.checkIfPingStored(player)).thenReturn(false);
        when(ftw.requestPingUrl(player)).thenReturn("https://example.test/ping");
        List<PickupReply> replies = new ArrayList<>();
        logic.queueAddPlayer(player, List.of(gametype), false, replies::add);

        io.runNext();
        queue.runNext();
        verify(match, never()).addPlayer(any());
        verify(player.getDiscordUser()).sendPrivateMessage(contains("https://example.test/ping"));
        assertEquals(Config.ftw_error_noping, replies.get(0).getMessage());
    }

    @Test
    void pickPromptUsesSeparateExecutorFromPendingJoin() throws Exception {
        Match draft = new Match(logic, gametype, List.of(), mock(PermissionService.class));
        set(draft, "state", MatchState.AwaitingServer);
        set(draft, "sortedPlayers", new ArrayList<>(List.of(player)));
        set(draft, "captains", new Player[]{player, player});
        logic.queueAddPlayer(player, List.of(gametype), false, reply -> {});

        draft.checkTeams();

        assertEquals(1, io.size());
        assertEquals(1, pickIo.size());
        assertFalse(queue.hasTasks());
    }

    @Test
    void afkCheckSkipsDraftUntilCaptainsAreSelected() throws Exception {
        when(match.getMatchState()).thenReturn(MatchState.AwaitingServer);
        when(match.getPlayerList()).thenReturn(List.of());
        @SuppressWarnings("unchecked")
        List<Match> ongoing = (List<Match>) get(logic, "ongoingMatches");
        ongoing.add(match);

        logic.afkCheck();

        verify(match, never()).reset();
    }

    @Test
    void rentalRunsOutsideQueueAndOnlyOneRequestLaunches() {
        when(match.getMatchState()).thenReturn(MatchState.AwaitingServer);
        when(match.getPlayerList()).thenReturn(List.of(player));
        when(match.getPlayerCount()).thenReturn(1);
        Server server = mock(Server.class);
        server.country = "US";
        server.city = "New York";
        when(ftw.spawnDynamicServer(any())).thenReturn(server);

        logic.requestServer(match);
        logic.requestServer(match);
        assertEquals(1, io.size());
        verify(ftw, never()).spawnDynamicServer(any());
        queue.execute(() -> {});
        queue.runNext();
        io.runNext();
        queue.runNext();
        verify(ftw).spawnDynamicServer(List.of(player));
        verify(match).launch(server);
    }

    @Test
    void cancelledOrReplacedMatchDiscardsLateRental() {
        when(match.getMatchState()).thenReturn(MatchState.AwaitingServer);
        when(match.getPlayerList()).thenReturn(List.of(player));
        when(match.getPlayerCount()).thenReturn(1);
        Server server = mock(Server.class);
        when(ftw.spawnDynamicServer(any())).thenReturn(server);
        logic.requestServer(match);
        io.runNext();
        logic.cancelRequestServer(match);
        queue.runNext();
        verify(match, never()).launch(any());
        verify(server).free();

        logic.requestServer(match);
        current.put(gametype, mock(Match.class));
        io.runNext();
        queue.runNext();
        verify(match, never()).launch(any());
        verify(server, times(2)).free();
    }

    @Test
    void cancelledRentalStillRunningCannotClearRefilledMatchRequestWhenACompletesFirst() {
        overlappingRentals(false);
    }

    @Test
    void cancelledRentalStillRunningCannotLaunchAfterRefilledMatchRequestCompletesFirst() {
        overlappingRentals(true);
    }

    private void overlappingRentals(boolean replacementCompletesFirst) {
        when(match.getMatchState()).thenReturn(MatchState.AwaitingServer);
        when(match.getPlayerList()).thenReturn(List.of(player)); // same roster after leaving and rejoining
        when(match.getPlayerCount()).thenReturn(1);
        Server stale = mock(Server.class);
        Server replacement = mock(Server.class);
        replacement.country = "US";
        replacement.city = "New York";
        when(ftw.spawnDynamicServer(List.of(player))).thenReturn(stale, replacement);

        logic.requestServer(match);
        io.runNext(); // A has rented, but its queue callback has not run
        logic.cancelRequestServer(match);
        logic.requestServer(match);
        logic.requestServer(match); // duplicate request must not launch a third rental
        assertEquals(1, io.size());
        io.runNext(); // both A and B callbacks are queued

        if (replacementCompletesFirst) {
            queue.runLast();
            queue.runNext();
        } else {
            queue.runNext();
            logic.requestServer(match); // A must not have cleared B's pending flag
            assertEquals(0, io.size());
            queue.runNext();
        }

        verify(ftw, times(2)).spawnDynamicServer(List.of(player));
        verify(match, times(1)).launch(any());
        verify(match).launch(replacement);
        verify(stale).free();
        verify(replacement, never()).free();
        assertFalse(queue.hasTasks());
        assertFalse(io.hasTasks());
    }

    @Test
    void cancellationBeforeRentalStartsSkipsTheObsoleteSpawn() {
        when(match.getMatchState()).thenReturn(MatchState.AwaitingServer);
        when(match.getPlayerList()).thenReturn(List.of(player));
        when(match.getPlayerCount()).thenReturn(1);
        Server replacement = mock(Server.class);
        replacement.country = "US";
        replacement.city = "New York";
        when(ftw.spawnDynamicServer(List.of(player))).thenReturn(replacement);

        logic.requestServer(match);
        logic.cancelRequestServer(match);
        logic.requestServer(match);
        io.runNext(); // cancelled A is still queued on the IO executor
        verify(ftw, never()).spawnDynamicServer(any());
        io.runNext();
        queue.runNext();

        verify(ftw).spawnDynamicServer(List.of(player));
        verify(match).launch(replacement);
        assertFalse(queue.hasTasks());
    }

    @Test
    void sameSizedRosterChangeDiscardsTheOldRental() {
        Player replacementPlayer = mock(Player.class);
        when(match.getMatchState()).thenReturn(MatchState.AwaitingServer);
        when(match.getPlayerList()).thenReturn(List.of(player), List.of(replacementPlayer));
        when(match.getPlayerCount()).thenReturn(1);
        Server stale = mock(Server.class);
        when(ftw.spawnDynamicServer(any())).thenReturn(stale);

        logic.requestServer(match);
        io.runNext();
        queue.runNext();

        verify(match, never()).launch(any());
        verify(stale).free();
    }

    @Test
    void changedPlayerListDiscardsRental() {
        when(match.getMatchState()).thenReturn(MatchState.AwaitingServer);
        when(match.getPlayerList()).thenReturn(List.of(player), List.of());
        when(match.getPlayerCount()).thenReturn(0);
        Server server = mock(Server.class);
        when(ftw.spawnDynamicServer(any())).thenReturn(server);
        logic.requestServer(match);
        io.runNext();
        queue.runNext();
        assertFalse(queue.hasTasks());
        verify(match, never()).launch(any());
        verify(server).free();
    }

    @Test
    void failedRentalResetsTheFullQueueWithoutLaunching() {
        when(match.getMatchState()).thenReturn(MatchState.AwaitingServer);
        when(match.getPlayerList()).thenReturn(List.of(player));
        when(match.getPlayerCount()).thenReturn(1);
        when(ftw.spawnDynamicServer(any())).thenReturn(null);
        logic.requestServer(match);

        io.runNext();
        queue.runNext();
        verify(match).reset();
        verify(match, never()).launch(any());
    }

    @Test
    void latePickPromptIsRemovedRatherThanReplacingCurrentButtons() throws Exception {
        PickupBot bot = spy(logic.bot);
        logic.bot = bot;
        DiscordMessage old = mock(DiscordMessage.class);
        DiscordMessage currentPrompt = mock(DiscordMessage.class);
        doReturn(List.of(old), List.of(currentPrompt)).when(bot).sendMsgToEdit(anyList(), anyString(), isNull(), anyList());
        when(player.getRank()).thenReturn(PlayerRank.SILVER);
        when(player.getDiscordUser().getMentionString()).thenReturn("<@123>");
        Match draft = new Match(logic, gametype, List.of(), mock(PermissionService.class));
        set(draft, "state", MatchState.AwaitingServer);
        set(draft, "sortedPlayers", new ArrayList<>(List.of(player)));
        set(draft, "captains", new Player[]{player, player});
        @SuppressWarnings("unchecked")
        List<Match> ongoing = (List<Match>) get(logic, "ongoingMatches");
        ongoing.add(draft);

        draft.checkTeams();
        draft.checkTeams();
        pickIo.runNext();
        pickIo.runNext();
        queue.runNext();
        queue.runNext();

        verify(old).delete();
        verify(currentPrompt, never()).delete();
        assertEquals(List.of(currentPrompt), get(draft, "pickMessages"));
    }

    @Test
    void captainCannotTimeOutBeforeButtonsAreDelivered() throws Exception {
        PickupBot bot = spy(logic.bot);
        logic.bot = bot;
        DiscordMessage prompt = mock(DiscordMessage.class);
        doReturn(List.of(prompt)).when(bot).sendMsgToEdit(anyList(), anyString(), isNull(), anyList());
        when(player.getRank()).thenReturn(PlayerRank.SILVER);
        when(player.getDiscordUser().getMentionString()).thenReturn("<@123>");
        Match draft = new Match(logic, gametype, List.of(), mock(PermissionService.class));
        set(draft, "state", MatchState.AwaitingServer);
        set(draft, "sortedPlayers", new ArrayList<>(List.of(player)));
        set(draft, "captains", new Player[]{player, player});
        set(draft, "timeLastPick", System.currentTimeMillis() - 10 * 60_000L);
        @SuppressWarnings("unchecked")
        List<Match> ongoing = (List<Match>) get(logic, "ongoingMatches");
        ongoing.add(draft);
        when(match.getPlayerList()).thenReturn(List.of());

        draft.checkTeams();
        logic.afkCheck();
        assertTrue(ongoing.contains(draft), "The captain has not received a prompt yet");
        assertTrue(draft.getTimeLastPick() > System.currentTimeMillis() - 60_000L);

        pickIo.runNext();
        queue.runNext();
        assertEquals(false, get(draft, "pickPromptPending"));
        assertTrue(draft.getTimeLastPick() > System.currentTimeMillis() - 60_000L);
    }

    @Test
    void failedPickPromptCancelsDraftWithoutBanningCaptain() throws Exception {
        PickupBot bot = spy(logic.bot);
        logic.bot = bot;
        doThrow(new IllegalStateException("Discord unavailable")).when(bot)
                .sendMsgToEdit(anyList(), anyString(), isNull(), anyList());
        when(player.getRank()).thenReturn(PlayerRank.SILVER);
        when(player.getDiscordUser().getMentionString()).thenReturn("<@123>");
        Match draft = new Match(logic, gametype, List.of(), mock(PermissionService.class));
        set(draft, "state", MatchState.AwaitingServer);
        set(draft, "sortedPlayers", new ArrayList<>(List.of(player)));
        set(draft, "captains", new Player[]{player, player});
        @SuppressWarnings("unchecked")
        List<Match> ongoing = (List<Match>) get(logic, "ongoingMatches");
        ongoing.add(draft);

        draft.checkTeams();
        pickIo.runNext();
        queue.runNext();

        assertFalse(ongoing.contains(draft));
        assertEquals(MatchState.Signup, draft.getMatchState());
        verify(logic.db, never()).createBan(any());
    }

    @Test
    void threadCreationAndRatingLookupHappenAfterQueueLaunchReturns() throws Exception {
        DiscordChannel channel = mock(DiscordChannel.class);
        DiscordChannel thread = mock(DiscordChannel.class);
        when(channel.createThread(any(), eq(true))).thenReturn(thread);
        PickupLogic draftLogic = mock(PickupLogic.class);
        set(draftLogic, "ftwglApi", ftw);
        draftLogic.bot = logic.bot;
        draftLogic.db = mock(Database.class);
        when(draftLogic.getChannelByType(PickupChannelType.PUBLIC)).thenReturn(List.of(channel));
        when(draftLogic.getDynamicServers()).thenReturn(true);
        when(draftLogic.isOngoingMatch(any())).thenReturn(true);
        when(ftw.getPlayerRatings(anyList())).thenReturn(Map.of());
        Match draft = spy(new Match(draftLogic, gametype, List.of(), mock(PermissionService.class)));
        set(draft, "state", MatchState.AwaitingServer);
        doReturn(List.of(player)).when(draft).getPlayerList();
        doNothing().when(draft).sortPlayers(anyMap(), any());
        Server server = mock(Server.class);

        draft.launch(server);
        verifyNoInteractions(channel);
        verify(ftw, never()).getPlayerRatings(anyList());
        assertEquals(1, io.size());

        io.runNext();
        verify(channel).createThread(any(), eq(true));
        verify(ftw).getPlayerRatings(List.of(player));
        verify(draft, never()).sortPlayers(anyMap(), any());
        queue.runNext();
        verify(draft).sortPlayers(anyMap(), any());
    }

    @Test
    void resetDuringThreadCreationDiscardsCreatedThreads() throws Exception {
        DiscordChannel channel = mock(DiscordChannel.class);
        DiscordChannel thread = mock(DiscordChannel.class);
        when(channel.createThread(any(), eq(true))).thenReturn(thread);
        PickupLogic draftLogic = mock(PickupLogic.class);
        set(draftLogic, "ftwglApi", ftw);
        draftLogic.bot = logic.bot;
        draftLogic.db = mock(Database.class);
        when(draftLogic.getChannelByType(PickupChannelType.PUBLIC)).thenReturn(List.of(channel));
        when(draftLogic.isOngoingMatch(any())).thenReturn(false);
        when(ftw.getPlayerRatings(anyList())).thenReturn(Map.of());
        Match draft = spy(new Match(draftLogic, gametype, List.of(), mock(PermissionService.class)));
        set(draft, "state", MatchState.AwaitingServer);
        doReturn(List.of(player)).when(draft).getPlayerList();
        Server server = mock(Server.class);

        draft.launch(server);
        draft.reset();
        io.runNext();
        queue.runNext();

        verify(thread).delete();
        verify(draft, never()).sortPlayers(anyMap(), any());
    }

    @Test
    void staleOrOutOfRangePickCannotBeApplied() throws Exception {
        Match draft = new Match(logic, gametype, List.of(), mock(PermissionService.class));
        set(draft, "state", MatchState.AwaitingServer);
        set(draft, "sortedPlayers", new ArrayList<>(List.of(player)));
        set(draft, "pickPromptGeneration", 2);
        String draftToken = (String) get(draft, "draftToken");

        assertTrue(draft.canPick(draftToken, 2, 0));
        assertFalse(draft.canPick("another-draft", 2, 0));
        assertFalse(draft.canPick(draftToken, 1, 0));
        assertFalse(draft.canPick(draftToken, 2, 1));
        assertFalse(draft.canPick(draftToken, 2, -1));
        set(draft, "state", MatchState.Signup);
        assertFalse(draft.canPick(draftToken, 2, 0));
    }

    @Test
    void oldDraftButtonCannotPickInAnotherMatchWithTheSameCaptain() throws Exception {
        Match previous = new Match(logic, gametype, List.of(), mock(PermissionService.class));
        Match currentDraft = new Match(logic, gametype, List.of(), mock(PermissionService.class));
        set(currentDraft, "state", MatchState.AwaitingServer);
        set(currentDraft, "sortedPlayers", new ArrayList<>(List.of(player)));
        set(currentDraft, "captains", new Player[]{player, player});
        set(currentDraft, "pickPromptGeneration", 2);
        @SuppressWarnings("unchecked")
        List<Match> ongoing = (List<Match>) get(logic, "ongoingMatches");
        ongoing.add(currentDraft);
        DiscordInteraction interaction = mock(DiscordInteraction.class);

        logic.cmdPick(interaction, player, (String) get(previous, "draftToken"), 2, 0);

        verify(interaction).respondEphemeral("This pick is no longer available. Use the latest buttons.");
        assertTrue(currentDraft.canPick((String) get(currentDraft, "draftToken"), 2, 0));
    }

    private static void set(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static Object get(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        void runNext() {
            assertTrue(hasTasks(), "Expected a queued task");
            tasks.remove().run();
        }

        void runLast() {
            assertTrue(hasTasks(), "Expected a queued task");
            tasks.removeLast().run();
        }

        boolean hasTasks() {
            return !tasks.isEmpty();
        }

        int size() {
            return tasks.size();
        }
    }
}
