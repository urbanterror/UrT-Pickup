package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.discord.*;
import de.gost0r.pickupbot.ftwgl.FtwglApi;
import de.gost0r.pickupbot.permission.PermissionService;
import de.gost0r.pickupbot.permission.PickupRoleCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SeasonListTest {
    private PickupLogic logic;
    private Database database;
    private DiscordInteraction interaction;
    private Player player;

    @BeforeEach
    void setup() {
        logic = new PickupLogic(mock(PickupBot.class), mock(FtwglApi.class), mock(DiscordService.class),
                mock(PermissionService.class), mock(PickupRoleCache.class));
        database = mock(Database.class);
        logic.db = database;
        interaction = mock(DiscordInteraction.class);
        player = mock(Player.class);
        when(player.getUrtauth()).thenReturn("bravo");
    }

    @Test
    void showsPreviousSeasonsNewestFirstWithUtcDateRangesAndSelectableIds() {
        logic.currentSeason = new Season(3, 0, 0);
        when(database.getSeason(2)).thenReturn(season(2, "2026-06-01T00:00:00Z", "2026-10-01T00:00:00Z"));
        when(database.getSeason(1)).thenReturn(season(1, "2026-02-01T00:00:00Z", "2026-05-31T23:59:59Z"));

        logic.showSeasonList(interaction, player);

        DiscordSelectMenu menu = responseMenu();
        assertEquals(Config.INT_SEASONSELECTED + "_bravo", menu.getCustomId());
        assertEquals(List.of(
                new DiscordSelectOption("Season 2 (2026-06 to 2026-10)", "2"),
                new DiscordSelectOption("Season 1 (2026-02 to 2026-05)", "1")), menu.getOptions());
        verify(database, never()).getSeason(3);
        verify(database, never()).getSeason(0);
    }

    @Test
    void missingSeasonDoesNotHideOlderAvailableSeasons() {
        logic.currentSeason = new Season(3, 0, 0);
        when(database.getSeason(1)).thenReturn(season(1, "2026-02-01T00:00:00Z", "2026-05-31T23:59:59Z"));

        logic.showSeasonList(interaction, player);

        assertEquals(List.of(new DiscordSelectOption("Season 1 (2026-02 to 2026-05)", "1")),
                responseMenu().getOptions());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void noPreviousSeasonsRepliesWithoutAnEmptyMenu(int currentSeason) {
        logic.currentSeason = new Season(currentSeason, 0, 0);

        logic.showSeasonList(interaction, player);

        verify(interaction).respondEphemeral("No previous seasons available.", null, null);
        verifyNoMoreInteractions(interaction);
    }

    private DiscordSelectMenu responseMenu() {
        ArgumentCaptor<ArrayList<DiscordComponent>> components = ArgumentCaptor.forClass(ArrayList.class);
        verify(interaction).respondEphemeral(isNull(), isNull(), components.capture());
        assertEquals(1, components.getValue().size());
        return assertInstanceOf(DiscordSelectMenu.class, components.getValue().getFirst());
    }

    private static Season season(int number, String start, String end) {
        return new Season(number, Instant.parse(start).toEpochMilli(), Instant.parse(end).toEpochMilli());
    }
}
