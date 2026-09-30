package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.discord.DiscordUser;
import de.gost0r.pickupbot.ftwgl.FtwglApi;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StatsCommandCacheTest {
    private final AtomicLong clock = new AtomicLong();
    private final StatsCommandCache cache = new StatsCommandCache(clock::get);
    private final Database db = mock(Database.class);
    private final FtwglApi ftw = mock(FtwglApi.class);
    private final Season season = new Season(11, 0, 1000);
    private final Player player = player("1", "alpha");

    private StatsCommandCache.Values get(Player target) {
        return cache.get(target, season, true, db, ftw);
    }

    private void results(Player target, int rank, float rating) {
        when(db.getRankForPlayer(target)).thenReturn(rank);
        when(ftw.getPlayerRatings(List.of(target))).thenReturn(Map.of(target, rating));
    }

    @Test
    void reusesLookupsAcrossPlayerReloadsAndExpiresAfterSixtySeconds() {
        results(player, 3, 1.25f);
        assertEquals(new StatsCommandCache.Values(3, 1.25f), get(player));
        Player reloaded = player("1", "alpha");
        results(reloaded, 2, 1.5f);
        clock.set(Duration.ofSeconds(60).toNanos() - 1);
        assertEquals(new StatsCommandCache.Values(3, 1.25f), get(reloaded));
        verify(db, never()).getRankForPlayer(reloaded);
        verify(ftw, never()).getPlayerRatings(List.of(reloaded));

        clock.incrementAndGet();
        assertEquals(new StatsCommandCache.Values(2, 1.5f), get(reloaded));
        get(reloaded);
        verify(db).getRankForPlayer(reloaded);
        verify(ftw).getPlayerRatings(List.of(reloaded));
    }

    @Test
    void refreshesOnRevisionSeasonBoundaryAndIdentityChanges() {
        results(player, 3, 1.25f);
        get(player);
        results(player, 2, 1.5f);
        Player.invalidateSeasonStats();
        assertEquals(2, get(player).eloRank());
        cache.get(player, new Season(11, 1, 1000), true, db, ftw);
        cache.get(player, new Season(12, 1, 1000), true, db, ftw);
        verify(db, times(4)).getRankForPlayer(player);
        verify(ftw, times(4)).getPlayerRatings(List.of(player));

        Player renamed = player("1", "bravo");
        Player other = player("2", "alpha");
        results(renamed, 4, 2f);
        results(other, 5, 3f);
        assertEquals(4, get(renamed).eloRank());
        assertEquals(5, get(other).eloRank());
    }

    @Test
    void retriesFailuresButCachesSuccessfulZeroRatings() {
        when(db.getRankForPlayer(player)).thenReturn(3);
        when(ftw.getPlayerRatings(List.of(player))).thenReturn(Map.of(), Map.of(player, 0f));
        assertEquals(0f, get(player).rating());
        assertEquals(0f, get(player).rating());
        get(player);
        verify(ftw, times(2)).getPlayerRatings(List.of(player));

        Player.invalidateSeasonStats();
        when(db.getRankForPlayer(player)).thenReturn(-1, 2);
        assertEquals(-1, get(player).eloRank());
        assertEquals(2, get(player).eloRank());
        get(player);
        verify(db, times(4)).getRankForPlayer(player);
    }

    @Test
    void placementGamesOnlyLoadRankAndLaterLoadRatingWhenNeeded() {
        results(player, 3, 1.25f);
        cache.get(player, season, false, db, ftw);
        cache.get(player, season, false, db, ftw);
        verify(db).getRankForPlayer(player);
        verifyNoInteractions(ftw);
        assertEquals(1.25f, get(player).rating());
    }

    @Test
    void concurrentRequestsShareLookupWhileUnrelatedPlayersContinue() throws Exception {
        results(player, 3, 1.25f);
        Player other = player("2", "bravo");
        results(other, 4, 2f);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(ftw.getPlayerRatings(List.of(player))).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return Map.of(player, 1.25f);
        });
        try (var executor = Executors.newFixedThreadPool(3)) {
            try {
                var first = executor.submit(() -> get(player));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var second = executor.submit(() -> get(player));
                assertEquals(2f, executor.submit(() -> get(other)).get(5, TimeUnit.SECONDS).rating());
                release.countDown();
                assertEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
                verify(ftw).getPlayerRatings(List.of(player));
                verify(db).getRankForPlayer(player);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void revisionChangedDuringLookupIsNotReused() {
        results(player, 3, 1.25f);
        when(ftw.getPlayerRatings(List.of(player))).thenAnswer(invocation -> {
            Player.invalidateSeasonStats();
            return Map.of(player, 1.25f);
        });
        get(player);
        get(player);
        verify(ftw, times(2)).getPlayerRatings(List.of(player));
    }

    @Test
    void rendererReusesLookupsButCreatesFreshRepliesWithLiveProfileAndWallet() {
        results(player, 3, 1.25f);
        PickupLogic logic = spy(new PickupLogic(null, ftw, null, null, null));
        logic.db = db;
        logic.currentSeason = season;
        doReturn(null).when(logic).getGametypeByString(anyString());
        PlayerStats stats = new PlayerStats();
        stats.ts_wdl.win = 5;
        player.setCurrentSeasonStats(stats, season, Player.currentSeasonStatsRevision());

        PickupReply first = logic.cmdGetStats(player);
        player.setCoins(2000);
        player.setCountry("US");
        player.hydrateBoost(System.currentTimeMillis() + 60_000, 0, 0);
        first.getEmbed().setTitle("modified");
        first.getComponents().clear();
        PickupReply second = logic.cmdGetStats(player);
        assertEquals("2000", second.getEmbed().getFooterText());
        assertTrue(second.getEmbed().getTitle().contains(":flag_us:"));
        assertTrue(second.getEmbed().getDescription().contains("ELO BOOST"));
        assertEquals(3, second.getComponents().size());
        player.hydrateBoost(0, 0, 0);
        assertFalse(logic.cmdGetStats(player).getEmbed().getDescription().contains("ELO BOOST"));
        logic.cmdGetElo(player, new Gametype("TS", 5, true, false));
        verify(db).getRankForPlayer(player);
        verify(db, never()).getPlayerStats(any(), any());
        verify(ftw).getPlayerRatings(List.of(player));
    }

    private static Player player(String id, String auth) {
        DiscordUser user = mock(DiscordUser.class);
        when(user.getId()).thenReturn(id);
        return Player.detached(user, auth);
    }
}
