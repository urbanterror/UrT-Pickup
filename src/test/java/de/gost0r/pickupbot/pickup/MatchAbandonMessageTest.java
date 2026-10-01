package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.permission.PermissionService;
import de.gost0r.pickupbot.pickup.MatchStats.Status;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class MatchAbandonMessageTest {
    static Stream<Arguments> abandonmentMessages() {
        return Stream.of(
                new MessageCase("AIM", 2, Status.NOSHOW, "did not join the server. No automatic ban applies to this game mode."),
                new MessageCase("AIM", 2, Status.RAGEQUIT, "left the match. No automatic ban applies to this game mode."),
                new MessageCase("1V1", 1, Status.NOSHOW, "did not join the server. No automatic ban applies to this game mode."),
                new MessageCase("1V1", 1, Status.RAGEQUIT, "left the match. No automatic ban applies to this game mode."),
                new MessageCase("2V2", 2, Status.NOSHOW, "did not join the server. No automatic ban applies to this game mode."),
                new MessageCase("2V2", 2, Status.RAGEQUIT, "left the match. No automatic ban applies to this game mode."),
                new MessageCase("3V3", 3, Status.NOSHOW, null),
                new MessageCase("3V3", 3, Status.RAGEQUIT, null),
                new MessageCase("TS", 5, Status.NOSHOW, null),
                new MessageCase("TS", 5, Status.RAGEQUIT, null)
        ).flatMap(testCase -> Stream.of(0, 1, 2).map(playerCount -> {
            String playerMessage = "";
            if (playerCount > 0) {
                String names = playerCount == 1 ? "dilseth" : "dilseth alpha";
                String outcome = testCase.unpunishedOutcome() != null
                        ? testCase.unpunishedOutcome()
                        : (playerCount == 1 ? "was" : "were") + " punished accordingly.";
                playerMessage = "\n" + names + " " + outcome;
            }
            String expected = "**" + testCase.mode() + "**: Aftermath #43867 (ut4_turnpike_2v2):"
                    + "\nMatch was abandoned due to **" + testCase.status() + "**."
                    + playerMessage;
            return Arguments.of(testCase.mode(), testCase.teamSize(), testCase.status(), playerCount, expected);
        }));
    }

    @ParameterizedTest(name = "{0}, {2}, {3} involved players")
    @MethodSource("abandonmentMessages")
    void abandonmentAnnouncesAccurateOutcome(String mode, int teamSize, Status status,
                                            int playerCount, String expected) throws Exception {
        PickupLogic logic = mock(PickupLogic.class);
        logic.bot = mock(PickupBot.class);
        logic.db = mock(Database.class);
        Match match = new Match(logic, new Gametype(mode, teamSize, true, false),
                List.of(), mock(PermissionService.class));
        Field id = Match.class.getDeclaredField("id");
        id.setAccessible(true);
        id.set(match, 43867);
        Field map = Match.class.getDeclaredField("map");
        map.setAccessible(true);
        map.set(match, new GameMap("ut4_turnpike_2v2"));

        Player dilseth = mock(Player.class);
        when(dilseth.getUrtauth()).thenReturn("dilseth");
        Player alpha = mock(Player.class);
        when(alpha.getUrtauth()).thenReturn("alpha");
        List<Player> involvedPlayers = List.of(dilseth, alpha).subList(0, playerCount);

        match.abandon(status, involvedPlayers);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(logic.bot).sendMsg(eq(logic.getChannelByType(PickupChannelType.PUBLIC)), message.capture());
        assertEquals(expected, message.getValue());
    }

    private record MessageCase(String mode, int teamSize, Status status, String unpunishedOutcome) {}
}
