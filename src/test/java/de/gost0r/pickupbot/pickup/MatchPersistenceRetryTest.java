package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.discord.DiscordEmbed;
import de.gost0r.pickupbot.permission.PermissionService;
import de.gost0r.pickupbot.pickup.server.Server;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class MatchPersistenceRetryTest {
    @ParameterizedTest
    @EnumSource(value = MatchState.class, names = {"Done", "Mercy", "Surrender"})
    void resultWarmsCommandCacheInBackgroundOnlyAfterPersistenceSucceeds(MatchState state) throws Exception {
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
        doThrow(new MatchPersistenceException("disk unavailable"))
                .doNothing().when(logic.db).saveMatch(match);
        long revision = Player.currentSeasonStatsRevision();

        if (state == MatchState.Surrender) match.checkSurrender();
        else match.end();

        assertNull(refresh.get());
        assertEquals(revision, Player.currentSeasonStatsRevision());
        verify(logic, never()).warmStatsCommandCache(anyMap(), any(), anyLong());
        match.retryPendingSave();
        assertEquals(state, match.getMatchState());
        assertNotNull(refresh.get());
        assertTrue(Player.currentSeasonStatsRevision() > revision);
        verify(player, never()).refreshCurrentSeasonStats(any(), any());
        verify(logic, never()).warmStatsCommandCache(anyMap(), any(), anyLong());

        refresh.get().run();

        var order = inOrder(logic.db, player, logic);
        order.verify(logic.db).settleMatch(match.getID());
        order.verify(player).refreshCurrentSeasonStats(logic.db, logic.currentSeason);
        order.verify(logic.db).getRankForPlayer(player);
        order.verify(logic).warmStatsCommandCache(Map.of(player, 2), logic.currentSeason,
                Player.currentSeasonStatsRevision());
        refresh.set(null);
        match.retryPendingSave();
        assertNull(refresh.get());
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
