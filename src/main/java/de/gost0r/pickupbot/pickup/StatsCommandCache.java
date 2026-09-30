package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.ftwgl.FtwglApi;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** Memoizes the external lookups shared by !stats and !elo, not their mutable replies. */
final class StatsCommandCache {
    private static final long TTL_NANOS = Duration.ofSeconds(60).toNanos();
    private static final int MAX_ENTRIES = 1024;

    record Values(int eloRank, float rating) { }

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

    Values get(Player player, Season season, boolean includeRating, Database db, FtwglApi ftw) {
        Key key = new Key(player.getDiscordUser().getId(), player.getUrtauth(),
                season.number, season.startdate, season.enddate,
                Player.currentSeasonStatsRevision(), includeRating);
        Entry entry;
        synchronized (entries) {
            entry = entries.computeIfAbsent(key, ignored -> new Entry());
        }
        // Coalesce concurrent requests for this player without holding a global or Player lock over I/O.
        synchronized (entry) {
            if (entry.values != null && nanoTime.getAsLong() - entry.loadedAt < TTL_NANOS) {
                return entry.values;
            }
            int rank = db.getRankForPlayer(player);
            Map<Player, Float> ratings = includeRating ? ftw.getPlayerRatings(List.of(player)) : Map.of();
            Values values = new Values(rank, ratings.getOrDefault(player, 0f));
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
