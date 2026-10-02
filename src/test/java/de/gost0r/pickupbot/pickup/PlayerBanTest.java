package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.discord.DiscordUser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlayerBanTest {

    @Test
    void parsesStoredBanReasonsCaseInsensitively() {
        assertEquals(PlayerBan.BanReason.RACISM, PlayerBan.BanReason.fromStorage("RACISM"));
        assertEquals(PlayerBan.BanReason.RAGEQUIT, PlayerBan.BanReason.fromStorage(" ragequit "));
    }

    @Test
    void returnsNullForUnknownStoredBanReasons() {
        assertNull(PlayerBan.BanReason.fromStorage(null));
        assertNull(PlayerBan.BanReason.fromStorage(""));
        assertNull(PlayerBan.BanReason.fromStorage("NOT_A_REASON"));
    }

    @Test
    void banInfoShowsAllTimeTotalsAndSelectedHistoryWindow() {
        Player player = mock(Player.class);
        DiscordUser user = mock(DiscordUser.class);
        when(player.getDiscordUser()).thenReturn(user);
        when(user.getMentionString()).thenReturn("<@1>");
        when(player.getUrtauth()).thenReturn("testplayer");
        when(player.getLatestBan()).thenReturn(null);

        PlayerBan first = banWithDuration(30 * 60 * 1000L);
        PlayerBan second = banWithDuration(90 * 60 * 1000L);
        when(player.getPlayerBanListSince(0)).thenReturn(new ArrayList<>(List.of(first, second)));
        when(player.getPlayerBanListSince(longThat(time -> time != 0)))
                .thenReturn(new ArrayList<>(List.of(second)));

        PickupLogic logic = new PickupLogic(null, null, null, null, null);
        String standard = logic.printBanInfo(player);
        String extended = logic.printBanInfo(player, true);

        assertTrue(standard.contains("**Total bans:** 2 | **Total duration:** 2h"));
        assertTrue(standard.contains("Past 2 months"));
        assertTrue(extended.contains("Past 6 months"));
    }

    private static PlayerBan banWithDuration(long duration) {
        PlayerBan ban = new PlayerBan();
        ban.startTime = 1;
        ban.endTime = ban.startTime + duration;
        ban.reason = PlayerBan.BanReason.NOSHOW;
        return ban;
    }
}
