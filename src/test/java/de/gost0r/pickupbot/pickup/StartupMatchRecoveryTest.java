package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.discord.DiscordService;
import de.gost0r.pickupbot.ftwgl.FtwglApi;
import de.gost0r.pickupbot.permission.PermissionService;
import de.gost0r.pickupbot.permission.PickupRoleCache;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StartupMatchRecoveryTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingServerDuringRecoveryDoesNotFailStartupAndRetainsOnlyPendingResults(boolean saveFails) {
        PickupLogic logic = new PickupLogic(mock(PickupBot.class), mock(FtwglApi.class),
                mock(DiscordService.class), mock(PermissionService.class), mock(PickupRoleCache.class));
        Database previousDatabase = Player.db;
        PickupLogic previousLogic = Player.logic;
        PickupLogic previousBetLogic = Bet.logic;
        AtomicReference<Match> recovered = new AtomicReference<>();
        try (var databases = mockConstruction(Database.class, (database, context) -> {
            if (saveFails) {
                doThrow(new MatchPersistenceException("Temporary write failure"))
                        .doNothing().when(database).saveMatch(any(Match.class));
            }
            when(database.loadOngoingMatches()).thenAnswer(ignored -> {
                Match match = new Match(42, 0, new GameMap("ut4_casa"), new int[]{0, 0}, new int[]{0, 0},
                        Map.of("red", List.of(), "blue", List.of()), MatchState.Live,
                        new Gametype("TS", 1, true, false), null, Map.of(), logic, mock(PermissionService.class));
                recovered.set(match);
                return List.of(match);
            });
        })) {
            assertDoesNotThrow(logic::init);
            Match match = recovered.get();
            assertEquals(MatchState.Abort, match.getMatchState());
            assertEquals(saveFails, match.isPersistencePending());
            assertEquals(saveFails, logic.isOngoingMatch(match));
            assertTrue(logic.getPublicLiveMatches().isEmpty());
            logic.retryPendingMatchSaves();
            assertFalse(match.isPersistencePending());
            assertFalse(logic.isOngoingMatch(match));
            Database database = databases.constructed().getFirst();
            verify(database, times(saveFails ? 2 : 1)).saveMatch(match);
            verify(database, times(1)).settleMatch(42);
        } finally {
            Player.db = previousDatabase;
            Player.logic = previousLogic;
            Bet.logic = previousBetLogic;
        }
    }
}
