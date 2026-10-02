package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.ftwgl.FtwglApi;
import de.gost0r.pickupbot.ftwgl.models.PlayerRating;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** Memoizes the external lookups shared by !stats and !elo, not their mutable replies. */
final class StatsCommandCache {
    private static final long TTL_NANOS = Duration.ofSeconds(60).toNanos();
    private static final int MAX_ENTRIES = 1024;

    record Values(int eloRank, PlayerRating rating) { }

    private record Key(String userId, String auth, int season, long start, long end,
                       long revision, boolean includeRating) { }

    private static final class Entry {
        Values values;
        long loadedAt;
    }

    private final Map<Key, Entry> entries = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Entry> eldest) {
            return size() > MAX_ENTRIES;
        }
    };
    private final LongSupplier nanoTime;

    StatsCommandCache() {
        this(System::nanoTime);
    }

    StatsCommandCache(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    void warm(Map<Player, Integer> ranks, Season season, long revision, FtwglApi ftw) {
        if (Player.currentSeasonStatsRevision() != revision) return;
        List<Player> ratedPlayers = ranks.keySet().stream()
                .filter(player -> ranks.get(player) > 0 && player.stats.ts_wdl.getTotal() >= 5)
                .toList();
        // Match participants share one FTW request; ranks were already read during stats refresh.
        Map<Player, PlayerRating> ratings = ratedPlayers.isEmpty() ? Map.of() : ftw.getPlayerRatings(ratedPlayers, season);
        if (Player.currentSeasonStatsRevision() != revision) return;
        ranks.forEach((player, rank) -> {
            if (rank <= 0) return;
            store(player, season, revision, false, new Values(rank, PlayerRating.ZERO));
            if (ratings.containsKey(player)) {
                store(player, season, revision, true, new Values(rank, ratings.get(player)));
            }
        });
    }

    private void store(Player player, Season season, long revision, boolean includeRating, Values values) {
        Key key = new Key(player.getDiscordUser().getId(), player.getUrtauth(),
                season.number, season.startdate, season.enddate, revision, includeRating);
        Entry entry = entryFor(key);
        synchronized (entry) {
            // A command may have populated this entry while the batch request was in flight.
            if (Player.currentSeasonStatsRevision() == revision
                    && (entry.values == null || nanoTime.getAsLong() - entry.loadedAt >= TTL_NANOS)) {
                entry.values = values;
                entry.loadedAt = nanoTime.getAsLong();
            }
        }
    }

    private Entry entryFor(Key key) {
        synchronized (entries) {
            return entries.computeIfAbsent(key, ignored -> new Entry());
        }
    }

    Values get(Player player, Season season, boolean includeRating, Database db, FtwglApi ftw) {
        Key key = new Key(player.getDiscordUser().getId(), player.getUrtauth(),
                season.number, season.startdate, season.enddate,
                Player.currentSeasonStatsRevision(), includeRating);
        Entry entry = entryFor(key);
        // Coalesce concurrent requests for this player without holding a global or Player lock over I/O.
        synchronized (entry) {
            if (entry.values != null && nanoTime.getAsLong() - entry.loadedAt < TTL_NANOS) {
                return entry.values;
            }
            int rank = db.getRankForPlayer(player);
            Map<Player, PlayerRating> ratings = includeRating ? ftw.getPlayerRatings(List.of(player), season) : Map.of();
            Values values = new Values(rank, ratings.getOrDefault(player, PlayerRating.ZERO));
            // The API's empty map and database's -1 are failure fallbacks, not reusable results.
            if (rank > 0 && (!includeRating || ratings.containsKey(player))
                    && Player.currentSeasonStatsRevision() == key.revision()) {
                entry.values = values;
                entry.loadedAt = nanoTime.getAsLong();
            }
            return values;
        }
    }
}
