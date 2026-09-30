package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.permission.PermissionService;
import de.gost0r.pickupbot.pickup.server.Server;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class MatchPersistenceRetryTest {
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
