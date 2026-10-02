package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.discord.DiscordUser;
import de.gost0r.pickupbot.ftwgl.FtwglApi;
import de.gost0r.pickupbot.ftwgl.models.PlayerRating;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.LinkedHashMap;
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
        when(ftw.getPlayerRatings(eq(List.of(target)), any(Season.class))).thenReturn(Map.of(target, rating(rating)));
    }

    @Test
    void reusesLookupsAcrossPlayerReloadsAndExpiresAfterSixtySeconds() {
        results(player, 3, 1.25f);
        assertEquals(new StatsCommandCache.Values(3, rating(1.25f)), get(player));
        Player reloaded = player("1", "alpha");
        results(reloaded, 2, 1.5f);
        clock.set(Duration.ofSeconds(60).toNanos() - 1);
        assertEquals(new StatsCommandCache.Values(3, rating(1.25f)), get(reloaded));
        verify(db, never()).getRankForPlayer(reloaded);
        verify(ftw, never()).getPlayerRatings(List.of(reloaded), season);

        clock.incrementAndGet();
        assertEquals(new StatsCommandCache.Values(2, rating(1.5f)), get(reloaded));
        get(reloaded);
        verify(db).getRankForPlayer(reloaded);
        verify(ftw).getPlayerRatings(List.of(reloaded), season);
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
        verify(ftw, times(4)).getPlayerRatings(eq(List.of(player)), any(Season.class));

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
        when(ftw.getPlayerRatings(List.of(player), season)).thenReturn(Map.of(), Map.of(player, PlayerRating.ZERO));
        assertEquals(PlayerRating.ZERO, get(player).rating());
        assertEquals(PlayerRating.ZERO, get(player).rating());
        get(player);
        verify(ftw, times(2)).getPlayerRatings(List.of(player), season);

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
        assertEquals(rating(1.25f), get(player).rating());
    }

    @Test
    void concurrentRequestsShareLookupWhileUnrelatedPlayersContinue() throws Exception {
        results(player, 3, 1.25f);
        Player other = player("2", "bravo");
        results(other, 4, 2f);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(ftw.getPlayerRatings(List.of(player), season)).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return Map.of(player, rating(1.25f));
        });
        try (var executor = Executors.newFixedThreadPool(3)) {
            try {
                var first = executor.submit(() -> get(player));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var second = executor.submit(() -> get(player));
                assertEquals(rating(2f), executor.submit(() -> get(other)).get(5, TimeUnit.SECONDS).rating());
                release.countDown();
                assertEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
                verify(ftw).getPlayerRatings(List.of(player), season);
                verify(db).getRankForPlayer(player);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void revisionChangedDuringLookupIsNotReused() {
        results(player, 3, 1.25f);
        when(ftw.getPlayerRatings(List.of(player), season)).thenAnswer(invocation -> {
            Player.invalidateSeasonStats();
            return Map.of(player, rating(1.25f));
        });
        get(player);
        get(player);
        verify(ftw, times(2)).getPlayerRatings(List.of(player), season);
    }

    @Test
    void postMatchBatchPopulatesParticipantsIncludingPlacementPlayersWithoutMoreQueries() {
        Player other = player("2", "bravo");
        Player placement = player("3", "charlie");
        player.stats.ts_wdl.win = 5;
        other.stats.ts_wdl.win = 6;
        Map<Player, Integer> ranks = new LinkedHashMap<>();
        ranks.put(player, 2);
        ranks.put(other, 3);
        ranks.put(placement, 4);
        when(ftw.getPlayerRatings(List.of(player, other), season)).thenReturn(Map.of(player, rating(1.5f), other, rating(2f)));

        cache.warm(ranks, season, Player.currentSeasonStatsRevision(), ftw);

        assertEquals(new StatsCommandCache.Values(2, rating(1.5f)), get(player));
        assertEquals(new StatsCommandCache.Values(3, rating(2f)), get(other));
        assertEquals(4, cache.get(placement, season, false, db, ftw).eloRank());
        verifyNoInteractions(db);
        verify(ftw).getPlayerRatings(List.of(player, other), season);
        verifyNoMoreInteractions(ftw);
    }

    @Test
    void failedPostMatchFetchIsRetriedOnDemand() {
        player.stats.ts_wdl.win = 5;
        when(ftw.getPlayerRatings(List.of(player), season)).thenReturn(Map.of(), Map.of(player, rating(1.5f)));
        when(db.getRankForPlayer(player)).thenReturn(2);

        cache.warm(Map.of(player, 2), season, Player.currentSeasonStatsRevision(), ftw);

        assertEquals(rating(1.5f), get(player).rating());
        verify(ftw, times(2)).getPlayerRatings(List.of(player), season);
    }

    @Test
    void postMatchBatchCannotPopulateCacheForANewerRevision() {
        player.stats.ts_wdl.win = 5;
        when(ftw.getPlayerRatings(List.of(player), season)).thenAnswer(invocation -> {
            Player.invalidateSeasonStats();
            return Map.of(player, rating(1.25f));
        });
        cache.warm(Map.of(player, 3), season, Player.currentSeasonStatsRevision(), ftw);
        results(player, 2, 1.5f);

        assertEquals(new StatsCommandCache.Values(2, rating(1.5f)), get(player));
        verify(ftw, times(2)).getPlayerRatings(List.of(player), season);
        verify(db).getRankForPlayer(player);
    }

    @Test
    void slowPostMatchBatchDoesNotOverwriteFresherCommandLookup() throws Exception {
        player.stats.ts_wdl.win = 5;
        when(db.getRankForPlayer(player)).thenReturn(2);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(ftw.getPlayerRatings(List.of(player), season)).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return Map.of(player, rating(1.25f));
        }).thenReturn(Map.of(player, rating(1.5f)));
        long revision = Player.currentSeasonStatsRevision();

        try (var executor = Executors.newFixedThreadPool(2)) {
            try {
                var batch = executor.submit(() -> cache.warm(Map.of(player, 3), season, revision, ftw));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var expected = new StatsCommandCache.Values(2, rating(1.5f));
                assertEquals(expected, executor.submit(() -> get(player)).get(5, TimeUnit.SECONDS));
                release.countDown();
                batch.get(5, TimeUnit.SECONDS);
                assertEquals(expected, get(player));
                verify(db).getRankForPlayer(player);
                verify(ftw, times(2)).getPlayerRatings(List.of(player), season);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void commandsReuseThePostMatchWarmup() {
        PickupLogic logic = spy(new PickupLogic(null, ftw, null, null, null));
        logic.db = db;
        logic.currentSeason = season;
        doReturn(null).when(logic).getGametypeByString(anyString());
        player.stats.ts_wdl.win = 5;
        player.setCurrentSeasonStats(player.stats, season, Player.currentSeasonStatsRevision());
        when(ftw.getPlayerRatings(List.of(player), season)).thenReturn(Map.of(player, rating(1.5f)));

        logic.warmStatsCommandCache(Map.of(player, 2), season, Player.currentSeasonStatsRevision());

        assertTrue(logic.cmdGetStats(player).getEmbed().getDescription().contains("#2"));
        logic.cmdGetElo(player, new Gametype("TS", 5, true, false));
        verifyNoInteractions(db);
        verify(ftw).getPlayerRatings(List.of(player), season);
        verifyNoMoreInteractions(ftw);
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
        verify(db, never()).tryGetPlayerStats(any(), any());
        verify(ftw).getPlayerRatings(List.of(player), season);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void commandsShowRatingsBeforeAndAfterSeasonPlacements(int games) {
        results(player, 3, 1.25f);
        PickupLogic logic = spy(new PickupLogic(null, ftw, null, null, null));
        logic.db = db;
        logic.currentSeason = season;
        doReturn(null).when(logic).getGametypeByString(anyString());
        PlayerStats stats = new PlayerStats();
        stats.ts_wdl.win = games;
        stats.ctf_wdl.win = games;
        stats.ctf_rating = 2.5f;
        stats.ctfRank = -1;
        player.setCurrentSeasonStats(stats, season, Player.currentSeasonStatsRevision());

        var embed = logic.cmdGetStats(player).getEmbed();
        assertEquals(List.of(String.format("%.02f", 1.25f), String.format("%.02f", 1.75f), String.format("%.02f", 2.5f)),
                embed.getFields().stream().filter(field -> field.name().toLowerCase().endsWith("rating"))
                        .map(field -> field.value()).toList());
        if (games < 5) {
            assertTrue(embed.getFields().stream().anyMatch(field ->
                    field.value().equals("**TS**: ``" + games + "/5`` placement games")));
            assertTrue(embed.getFields().stream().anyMatch(field ->
                    field.value().equals("**CTF**: ``" + games + "/5`` placement games")));
        } else {
            assertFalse(embed.getFields().stream().anyMatch(field -> field.value().contains("placement games")));
        }
        assertTrue(logic.cmdGetElo(player, new Gametype("TS", 5, true, false))
                .endsWith(rating(1.25f).display()));
        assertTrue(logic.cmdGetElo(player, new Gametype("CTF", 5, true, false))
                .endsWith(String.format("%.02f", 2.5f)));
        verify(ftw).getPlayerRatings(List.of(player), season);
        verify(db, never()).getPlayerStats(any(), any());
        verify(db, never()).tryGetPlayerStats(any(), any());
    }

    private static PlayerRating rating(float seasonRating) {
        return new PlayerRating(seasonRating + 0.5f, seasonRating);
    }

    private static Player player(String id, String auth) {
        DiscordUser user = mock(DiscordUser.class);
        when(user.getId()).thenReturn(id);
        return Player.detached(user, auth);
    }
}
