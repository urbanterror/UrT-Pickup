package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.discord.DiscordEmbed;
import de.gost0r.pickupbot.discord.DiscordUser;
import de.gost0r.pickupbot.ftwgl.FtwglApi;
import de.gost0r.pickupbot.ftwgl.models.PlayerRating;
import de.gost0r.pickupbot.permission.PermissionService;
import de.gost0r.pickupbot.pickup.server.Server;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class MatchPersistenceRetryTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nextStatsResponseUsesSavedResultWithBackgroundRefreshOrRejectedExecutor(boolean rejectRefresh) throws Exception {
        FtwglApi ftw = mock(FtwglApi.class);
        PickupBot bot = mock(PickupBot.class);
        PickupLogic logic = spy(new PickupLogic(bot, ftw, null, null, null));
        logic.db = mock(Database.class);
        logic.currentSeason = new Season(11, 0, 1000);
        doNothing().when(logic).matchEnded();
        doNothing().when(logic).matchRemove(any());
        doReturn(List.of()).when(logic).getChannelByType(any());
        doReturn(null).when(logic).getGametypeByString(anyString());
        AtomicReference<Runnable> refresh = new AtomicReference<>();
        setField(PickupBot.class, bot, "pickupIoExecutor", (Executor) task -> {
            if (rejectRefresh) throw new RejectedExecutionException("executor full");
            refresh.set(task);
        });
        DiscordUser user = mock(DiscordUser.class);
        when(user.getId()).thenReturn("1");
        Player player = spy(Player.detached(user, "alpha"));
        doNothing().when(player).refreshWallet();
        PlayerStats before = new PlayerStats();
        before.ts_wdl.win = 5;
        player.setCurrentSeasonStats(before, logic.currentSeason, Player.currentSeasonStatsRevision());
        when(logic.db.getRankForPlayer(player)).thenReturn(3);
        when(ftw.getPlayerRatings(List.of(player), logic.currentSeason)).thenReturn(Map.of(player, new PlayerRating(1.75f, 1.25f)));
        assertTrue(logic.cmdGetStats(player).getEmbed().getDescription().contains("#3"));

        PlayerStats after = new PlayerStats();
        after.ts_wdl.win = 6;
        when(logic.db.tryGetPlayerStats(player, logic.currentSeason)).thenReturn(after);
        when(logic.db.getRankForPlayer(player)).thenReturn(2);
        when(ftw.getPlayerRatings(List.of(player), logic.currentSeason)).thenReturn(Map.of(player, new PlayerRating(2f, 1.5f)));
        clearInvocations(logic.db, ftw);
        Match match = spy(new Match(logic, new Gametype("TS", 0, true, false),
                List.of(), mock(PermissionService.class)));
        setField(Match.class, match, "server", mock(Server.class, RETURNS_DEEP_STUBS));
        setField(Match.class, match, "map", new GameMap("ut4_turnpike"));
        setField(Match.class, match, "playerStats", Map.of(player, new MatchStats()));
        doReturn(new DiscordEmbed()).when(match).getMatchEmbed(false);

        assertDoesNotThrow(match::end);
        assertFalse(match.isPersistencePending());
        verify(logic).matchRemove(match);
        if (rejectRefresh) {
            assertNull(refresh.get());
        } else {
            assertNotNull(refresh.get());
            refresh.get().run();
            verify(logic.db).tryGetPlayerStats(player, logic.currentSeason);
            verify(ftw).getPlayerRatings(List.of(player), logic.currentSeason);
        }

        DiscordEmbed response = logic.cmdGetStats(player).getEmbed();
        assertTrue(response.getDescription().contains("#2"));
        assertTrue(response.getFields().contains(new DiscordEmbed.Field("Played", "6", true)));
        assertTrue(response.getFields().contains(new DiscordEmbed.Field("Season rating", String.format("%.02f", 1.5f), true)));
        assertTrue(response.getFields().contains(new DiscordEmbed.Field("All-time rating", String.format("%.02f", 2f), true)));
        logic.cmdGetStats(player);
        verify(logic.db).tryGetPlayerStats(player, logic.currentSeason);
        verify(logic.db).getRankForPlayer(player);
        verify(ftw).getPlayerRatings(List.of(player), logic.currentSeason);
    }

    @ParameterizedTest
    @CsvSource({"Done, false", "Mercy, false", "Surrender, false",
            "Done, true", "Mercy, true", "Surrender, true"})
    void resultWarmsCommandCacheInBackgroundOnlyAfterPersistenceSucceeds(MatchState state, boolean failSettlement) throws Exception {
        PickupLogic logic = mock(PickupLogic.class);
        logic.bot = mock(PickupBot.class);
        logic.db = mock(Database.class);
        logic.currentSeason = new Season(11, 0, 1000);
        AtomicReference<Runnable> refresh = new AtomicReference<>();
        setField(PickupBot.class, logic.bot, "pickupIoExecutor", (Executor) refresh::set);
        Player player = mock(Player.class);
        when(logic.db.getRankForPlayer(player)).thenReturn(2);
        Match match = spy(new Match(logic, new Gametype("TS", 0, true, false),
                List.of(), mock(PermissionService.class)));
        Server server = mock(Server.class, RETURNS_DEEP_STUBS);
        server.getServerMonitor().noMercyIssued = state == MatchState.Mercy;
        setField(Match.class, match, "server", server);
        setField(Match.class, match, "map", new GameMap("ut4_turnpike"));
        setField(Match.class, match, "playerStats", Map.of(player, new MatchStats()));
        setField(Match.class, match, "surrender", new int[]{0, 4});
        doReturn(new DiscordEmbed()).when(match).getMatchEmbed(false);
        if (failSettlement) {
            int matchId = match.getID();
            doThrow(new MatchPersistenceException("settlement unavailable"))
                    .doNothing().when(logic.db).settleMatch(matchId);
        } else {
            doThrow(new MatchPersistenceException("disk unavailable"))
                    .doNothing().when(logic.db).saveMatch(match);
        }
        long revision = Player.currentSeasonStatsRevision();

        if (state == MatchState.Surrender) match.checkSurrender();
        else match.end();

        assertNull(refresh.get());
        assertEquals(revision, Player.currentSeasonStatsRevision());
        verify(player, never()).refreshCurrentSeasonStats(any(), any());
        verify(logic, never()).warmStatsCommandCache(anyMap(), any(), anyLong());
        match.retryPendingSave();
        assertEquals(state, match.getMatchState());
        assertNotNull(refresh.get());
        assertTrue(Player.currentSeasonStatsRevision() > revision);
        verify(player, never()).refreshCurrentSeasonStats(any(), any());
        verify(logic, never()).warmStatsCommandCache(anyMap(), any(), anyLong());

        refresh.get().run();

        var order = inOrder(logic.db, player, logic);
        order.verify(logic.db, times(failSettlement ? 2 : 1)).settleMatch(match.getID());
        order.verify(player).refreshCurrentSeasonStats(logic.db, logic.currentSeason);
        order.verify(logic.db).getRankForPlayer(player);
        order.verify(logic).warmStatsCommandCache(Map.of(player, 2), logic.currentSeason,
                Player.currentSeasonStatsRevision());
        refresh.set(null);
        match.retryPendingSave();
        assertNull(refresh.get());
    }

    @ParameterizedTest
    @CsvSource({"Done, cleanup, false", "Mercy, cleanup, false", "Surrender, cleanup, false",
            "Done, notification, false", "Mercy, notification, false", "Surrender, notification, false",
            "Done, cleanup, true", "Mercy, cleanup, true", "Surrender, cleanup, true",
            "Done, notification, true", "Mercy, notification, true", "Surrender, notification, true"})
    void savedResultRemainsVisibleWhenCompletionThrows(MatchState state, String failurePoint,
                                                       boolean rejectRefresh) throws Exception {
        FtwglApi ftw = mock(FtwglApi.class);
        PickupBot bot = mock(PickupBot.class);
        PickupLogic logic = spy(new PickupLogic(bot, ftw, null, null, null));
        logic.db = mock(Database.class);
        logic.currentSeason = new Season(11, 0, 1000);
        doNothing().when(logic).matchEnded();
        doReturn(List.of()).when(logic).getChannelByType(any());
        doReturn(null).when(logic).getGametypeByString(anyString());
        Executor executor = mock(Executor.class);
        AtomicReference<Runnable> refresh = new AtomicReference<>();
        doAnswer(invocation -> {
            if (rejectRefresh) throw new RejectedExecutionException("executor full");
            refresh.set(invocation.getArgument(0));
            return null;
        }).when(executor).execute(any());
        setField(PickupBot.class, bot, "pickupIoExecutor", executor);
        DiscordUser user = mock(DiscordUser.class);
        when(user.getId()).thenReturn("1");
        Player player = spy(Player.detached(user, "alpha"));
        doNothing().when(player).refreshWallet();
        long revision = Player.currentSeasonStatsRevision();
        PlayerStats before = new PlayerStats();
        before.ts_wdl.win = 5;
        player.setCurrentSeasonStats(before, logic.currentSeason, revision);
        when(logic.db.getRankForPlayer(player)).thenReturn(3);
        when(ftw.getPlayerRatings(List.of(player), logic.currentSeason)).thenReturn(Map.of(player, new PlayerRating(1.75f, 1.25f)));
        assertTrue(logic.cmdGetStats(player).getEmbed().getDescription().contains("#3"));

        Match match = spy(new Match(logic, new Gametype("TS", 0, true, false),
                List.of(), mock(PermissionService.class)));
        Server server = mock(Server.class, RETURNS_DEEP_STUBS);
        server.getServerMonitor().noMercyIssued = state == MatchState.Mercy;
        setField(Match.class, match, "server", server);
        setField(Match.class, match, "map", new GameMap("ut4_turnpike"));
        setField(Match.class, match, "playerStats", Map.of(player, new MatchStats()));
        setField(Match.class, match, "surrender", new int[]{0, 4});
        doReturn(new DiscordEmbed()).when(match).getMatchEmbed(false);
        setField(PickupLogic.class, logic, "ongoingMatches", new CopyOnWriteArrayList<>(List.of(match)));
        RuntimeException failure = new IllegalStateException("completion failed");
        if (failurePoint.equals("cleanup")) {
            doThrow(failure).when(server).free();
        } else {
            doAnswer(invocation -> {
                assertNull(logic.playerInActiveMatch(player), "Players must be released before the result is posted");
                throw failure;
            }).when(bot).sendMsg(anyList(), anyString(), any(DiscordEmbed.class));
        }
        // Retry must retain the same guarantees as an immediately successful save.
        doThrow(new MatchPersistenceException("disk unavailable"))
                .doNothing().when(logic.db).saveMatch(match);
        if (state == MatchState.Surrender) match.checkSurrender();
        else match.end();
        assertTrue(match.isPersistencePending());
        assertSame(match, logic.playerInActiveMatch(player));
        assertEquals(revision, Player.currentSeasonStatsRevision());
        verifyNoInteractions(executor);

        PlayerStats after = new PlayerStats();
        after.ts_wdl.win = 6;
        when(logic.db.tryGetPlayerStats(player, logic.currentSeason)).thenReturn(after);
        when(logic.db.getRankForPlayer(player)).thenReturn(2);
        when(ftw.getPlayerRatings(List.of(player), logic.currentSeason)).thenReturn(Map.of(player, new PlayerRating(2f, 1.5f)));
        clearInvocations(logic.db, ftw);

        assertSame(failure, assertThrows(IllegalStateException.class, match::retryPendingSave));
        assertEquals(state, match.getMatchState());
        assertFalse(match.isPersistencePending());
        assertNull(logic.playerInActiveMatch(player));
        assertFalse(logic.isOngoingMatch(match));
        assertEquals(revision + 1, Player.currentSeasonStatsRevision());
        var order = inOrder(logic.db, executor, server, bot);
        order.verify(logic.db).saveMatch(match);
        order.verify(logic.db).settleMatch(match.getID());
        order.verify(executor).execute(any());
        order.verify(server).free();
        if (failurePoint.equals("notification")) {
            order.verify(bot).sendMsg(anyList(), anyString(), any(DiscordEmbed.class));
        }
        verify(logic.db, never()).tryGetPlayerStats(any(), any());
        verifyNoInteractions(ftw);
        verify(logic, never()).warmStatsCommandCache(anyMap(), any(), anyLong());

        if (rejectRefresh) {
            assertNull(refresh.get());
        } else {
            assertNotNull(refresh.get());
            refresh.get().run();
            verify(logic).warmStatsCommandCache(Map.of(player, 2), logic.currentSeason, revision + 1);
        }
        DiscordEmbed response = logic.cmdGetStats(player).getEmbed();
        assertTrue(response.getDescription().contains("#2"));
        assertTrue(response.getFields().contains(new DiscordEmbed.Field("Played", "6", true)));
        assertTrue(response.getFields().contains(new DiscordEmbed.Field("Season rating", String.format("%.02f", 1.5f), true)));
        assertTrue(response.getFields().contains(new DiscordEmbed.Field("All-time rating", String.format("%.02f", 2f), true)));
        verify(logic.db).tryGetPlayerStats(player, logic.currentSeason);
        verify(ftw).getPlayerRatings(List.of(player), logic.currentSeason);

        match.retryPendingSave();
        assertEquals(revision + 1, Player.currentSeasonStatsRevision());
        verify(executor).execute(any());
        verify(logic.db).saveMatch(match);
        verify(logic.db).settleMatch(match.getID());
        verify(server).free();
    }

    @ParameterizedTest
    @EnumSource(value = MatchState.class, names = {"Abort", "Abandon"})
    void unscoredCompletionDoesNotInvalidateOrScheduleStatsRefresh(MatchState state) throws Exception {
        PickupLogic logic = mock(PickupLogic.class);
        logic.bot = mock(PickupBot.class);
        logic.db = mock(Database.class);
        Executor executor = mock(Executor.class);
        setField(PickupBot.class, logic.bot, "pickupIoExecutor", executor);
        Match match = new Match(logic, new Gametype("TS", 0, true, false),
                List.of(), mock(PermissionService.class));
        setField(Match.class, match, "map", new GameMap("ut4_turnpike"));
        long revision = Player.currentSeasonStatsRevision();

        if (state == MatchState.Abort) match.abort();
        else match.abandon(MatchStats.Status.LEFT, List.of());

        verify(logic.db).saveMatch(match);
        verify(logic.db).settleMatch(match.getID());
        verify(logic).matchRemove(match);
        assertEquals(revision, Player.currentSeasonStatsRevision());
        verifyNoInteractions(executor);
        verify(logic, never()).warmStatsCommandCache(anyMap(), any(), anyLong());
    }

    private static void setField(Class<?> type, Object target, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void failedResultIsRetainedUntilRetrySucceedsAndCleanupRunsOnce() {
        PickupLogic logic = mock(PickupLogic.class);
        logic.bot = mock(PickupBot.class);
        logic.db = mock(Database.class);
        Match match = new Match(logic, new Gametype("TS", 0, true, false), List.of(), mock(PermissionService.class));
        doThrow(new MatchPersistenceException("disk unavailable", new SQLException("failure")))
                .doNothing().when(logic.db).saveMatch(match);

        match.abort();
        assertEquals(MatchState.Abort, match.getMatchState());
        verify(logic, never()).matchEnded();
        verify(logic, never()).matchRemove(match);
        match.abort(); // a repeated event must not overwrite the pending completion
        verify(logic.db, times(1)).saveMatch(match);

        match.retryPendingSave();
        verify(logic.db, times(2)).saveMatch(match);
        verify(logic, times(1)).matchEnded();
        verify(logic, times(1)).matchRemove(match);
        match.retryPendingSave();
        verify(logic.db, times(2)).saveMatch(match);
        verify(logic, times(1)).matchEnded();
    }

    @Test
    void failedSettlementKeepsCompletionPendingEvenAfterResultWasSaved() {
        PickupLogic logic = mock(PickupLogic.class);
        logic.bot = mock(PickupBot.class);
        logic.db = mock(Database.class);
        Match match = new Match(logic, new Gametype("TS", 0, true, false), List.of(), mock(PermissionService.class));
        doThrow(new MatchPersistenceException("settlement unavailable"))
                .doNothing().when(logic.db).settleMatch(match.getID());

        match.abort();
        assertEquals(true, match.isPersistencePending());
        verify(logic, never()).matchRemove(match);
        match.retryPendingSave();
        assertEquals(false, match.isPersistencePending());
        verify(logic.db, times(2)).saveMatch(match);
        verify(logic.db, times(2)).settleMatch(match.getID());
        verify(logic, times(1)).matchEnded();
    }

    @Test
    void failedCreationCancelsStartWithoutAnnouncingOrConfiguringServer() throws Exception {
        PickupLogic logic = mock(PickupLogic.class);
        logic.bot = mock(PickupBot.class);
        logic.db = mock(Database.class);
        Match match = new Match(logic, new Gametype("TS", 0, true, false),
                List.of(new GameMap("ut4_turnpike")), mock(PermissionService.class));
        Server server = mock(Server.class);
        Field field = Match.class.getDeclaredField("server");
        field.setAccessible(true);
        field.set(match, server);
        when(logic.db.createMatch(match)).thenThrow(new MatchPersistenceException("disk unavailable"));

        match.run();

        assertEquals(MatchState.Abort, match.getMatchState());
        verify(logic).matchRemove(match);
        verify(server, never()).sendRcon(anyString());
        verify(logic.bot).sendMsg(any(), contains("database record could not be saved"));
    }
}
