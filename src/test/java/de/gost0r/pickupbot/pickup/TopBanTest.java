package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.discord.DiscordEmbed;
import de.gost0r.pickupbot.discord.DiscordService;
import de.gost0r.pickupbot.discord.DiscordUser;
import de.gost0r.pickupbot.ftwgl.FtwglApi;
import de.gost0r.pickupbot.permission.PermissionService;
import de.gost0r.pickupbot.permission.PickupRoleCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TopBanTest {
    @TempDir Path directory;

    @Test
    void leaderboardCountsHistoricalBansAndShowsOnlyUnforgivenUnexpiredBansAsActive() {
        DiscordService discord = mock(DiscordService.class);
        PermissionService permissions = mock(PermissionService.class);
        FtwglApi ftw = mock(FtwglApi.class);
        PickupBot bot = new PickupBot(directory.resolve("ban-leaderboard").toString(), ftw, discord,
                permissions, mock(PickupRoleCache.class), Runnable::run, Runnable::run, Runnable::run, Runnable::run);
        PickupLogic logic = new PickupLogic(bot, ftw, discord, permissions, mock(PickupRoleCache.class));
        Database database = new Database(logic, discord, permissions);
        logic.db = database;
        try {
            assertEquals("None", logic.cmdTopBan(10).getMessage());

            Player alpha = player("1", "alpha");
            Player bravo = player("2", "bravo");
            Player charlie = player("3", "charlie");
            long now = System.currentTimeMillis();
            ban(database, alpha, now - 1000); // expired, still part of the all-time total
            ban(database, alpha, now + 86_400_000);
            database.forgiveBan(alpha); // forgiven, still part of the all-time total
            ban(database, alpha, now + 86_400_000);
            ban(database, bravo, now + 86_400_000);
            ban(database, bravo, now - 1000);
            ban(database, charlie, now - 1000);
            ban(database, charlie, now - 1000);

            assertEquals(List.of(new Database.BanCount("alpha", 3, 1),
                    new Database.BanCount("bravo", 2, 1), new Database.BanCount("charlie", 2, 0)),
                    database.getTopBans(10));
            assertEquals(List.of(new Database.BanCount("alpha", 3, 1)), database.getTopBans(1));

            DiscordEmbed embed = logic.cmdTopBan(2).getEmbed();
            assertEquals("Top 2 most banned players", embed.getTitle());
            assertTrue(embed.getDescription().contains("forgiven"));
            assertEquals("**1**\n**2**\n", embed.getFields().get(0).value());
            assertEquals("alpha\nbravo\n", embed.getFields().get(1).value());
            assertEquals("3 (1)\n2 (1)\n", embed.getFields().get(2).value());
        } finally {
            database.disconnect();
        }
    }

    private static Player player(String id, String auth) {
        DiscordUser user = mock(DiscordUser.class);
        when(user.getId()).thenReturn(id);
        Player player = mock(Player.class);
        when(player.getDiscordUser()).thenReturn(user);
        when(player.getUrtauth()).thenReturn(auth);
        return player;
    }

    private static void ban(Database database, Player player, long ends) {
        PlayerBan ban = new PlayerBan();
        ban.player = player;
        ban.startTime = System.currentTimeMillis() - 100_000;
        ban.endTime = ends;
        ban.reason = PlayerBan.BanReason.NOSHOW;
        database.createBan(ban);
    }
}
