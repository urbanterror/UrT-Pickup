package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.discord.DiscordUser;
import de.gost0r.pickupbot.pickup.stats.WinDrawLoss;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

public class Player {

    public static Database db;
    public static PickupLogic logic;

    private DiscordUser user;
    private String urtauth;

    private Map<Gametype, GameMap> votedMap = new HashMap<Gametype, GameMap>();
    private int elo = 1000;
    private int eloChange = 0;
    private int elorank = 0;

    private float kdr = 0.0f;

    public volatile PlayerStats stats = new PlayerStats();
    private volatile int statsSeason = -1;
    private volatile long statsRevision = -1;
    private static final AtomicLong seasonStatsRevision = new AtomicLong();

    static long currentSeasonStatsRevision() {
        return seasonStatsRevision.get();
    }

    static void invalidateSeasonStats() {
        seasonStatsRevision.incrementAndGet();
    }

    void setCurrentSeasonStats(PlayerStats updated, Season season, long revision) {
        setKdr(updated.kdr);
        stats = updated;
        statsRevision = revision;
        statsSeason = season.number;
    }

    /** SQL failure keeps the last complete snapshot, invalid for the next command's retry. */
    public synchronized void refreshCurrentSeasonStats(Database database, Season season) {
        long revision = currentSeasonStatsRevision();
        // Even a forced refresh of a current snapshot must remain retryable on failure.
        statsRevision = -1;
        PlayerStats updated = database.tryGetPlayerStats(this, season);
        if (updated != null) {
            setCurrentSeasonStats(updated, season, revision);
        }
    }

    public PlayerStats getCurrentSeasonStats(Database database, Season season) {
        if (statsSeason != season.number || statsRevision != currentSeasonStatsRevision()) {
            synchronized (this) {
                if (statsSeason != season.number || statsRevision != currentSeasonStatsRevision()) {
                    refreshCurrentSeasonStats(database, season);
                }
            }
        }
        return stats;
    }

    private List<PlayerBan> bans = new ArrayList<PlayerBan>();
    public Map<Gametype, Integer> spree = new HashMap<Gametype, Integer>();

    private boolean active = true;
    private boolean enforceAC = true;
    private boolean proctf = false;

    private boolean surrender = false;

    private long lastMessage = -1L;
    private boolean afkReminderSent = false;

    private String country = "NOT_DEFINED";

    private long coins = 1000;
    private long unsavedCoinDelta;
    private long eloBoost = 0;
    private int additionalMapVotes = 0;
    private int mapBans = 0;

    public Player(DiscordUser user, String urtauth) {
        this(user, urtauth, true);
    }

    private Player(DiscordUser user, String urtauth, boolean register) {
        this.user = user;
        this.setUrtauth(urtauth);
        if (register) {
            synchronized (Player.class) {
                invalidatedAt.put(identity(), ++lifecycleSequence);
                playerList.add(this);
            }
        }
    }

    // Database hydration must finish before this instance becomes cache-visible.
    static Player detached(DiscordUser user, String urtauth) {
        return new Player(user, urtauth, false);
    }

    public void voteMap(Gametype gametype, GameMap map) {
        votedMap.put(gametype, map);
    }

    public void voteSurrender() {
        surrender = true;
    }

    public void resetVotes() {
        for (Gametype gt : votedMap.keySet()) {
            votedMap.put(gt, null);
        }
        surrender = false;
    }

    public void addElo(int elochange) {
        this.elo += elochange;
        this.eloChange = elochange;

        // db update done by servermonitor
    }

    public void afkCheck() {
        lastMessage = System.currentTimeMillis();
        afkReminderSent = false;
    }

    public long getLastMessage() {
        return lastMessage;
    }

    public void setLastMessage(long lastMessage) {
        this.lastMessage = lastMessage;
    }

    public boolean getAfkReminderSent() {
        return afkReminderSent;
    }

    public void setAfkReminderSent(boolean value) {
        afkReminderSent = value;
    }

    public GameMap getVotedMap(Gametype gametype) {
        if (votedMap.containsKey(gametype)) {
            return votedMap.get(gametype);
        }
        return null;
    }

    public boolean hasVotedSurrender() {
        return surrender;
    }

    public DiscordUser getDiscordUser() {
        return user;
    }

    public int getElo() {
        return elo;
    }

    public void setElo(int elo) {
        if (elo <= 0) {
            this.elo = 1000;
        } else {
            this.elo = elo;
        }
    }

    public float getKdr() {
        return kdr;
    }

    public void setKdr(float kdr) {
        this.kdr = kdr;
    }

    public int getEloChange() {
        return eloChange;
    }

    public void setEloChange(int eloChange) {
        this.eloChange = eloChange;
    }

    public String getUrtauth() {
        return urtauth;
    }

    public void setUrtauth(String urtauth) {
        this.urtauth = urtauth;
    }

    public boolean getActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public void addBan(PlayerBan ban) {
        bans.add(ban);
    }

    public void forgiveBan() {
        for (PlayerBan ban : bans) {
            if (ban.endTime > System.currentTimeMillis()) {
                ban.forgiven = true;
            }
        }
        db.forgiveBan(this);
    }

    public void forgiveBotBan() {
        for (PlayerBan ban : bans) {
            if (ban.endTime > System.currentTimeMillis() && (ban.reason == PlayerBan.BanReason.NOSHOW || ban.reason == PlayerBan.BanReason.RAGEQUIT)) {
                ban.forgiven = true;
            }
        }
        db.forgiveBotBan(this);
    }

    public PlayerBan getLatestBan() {
        PlayerBan current = null;
        for (PlayerBan ban : bans) {
            if (current == null) {
                current = ban;
                continue;
            }
            if ((ban.startTime > current.startTime) && !ban.forgiven) {
                current = ban;
            }
        }
        return current;
    }

    public int getPlayerBanCountSince(long time) {
        int i = 0;
        for (PlayerBan ban : bans) {
            if (ban.startTime >= time && !ban.forgiven) {
                ++i;
            }
        }
        return i;
    }

    public ArrayList<PlayerBan> getPlayerBanListSince(long time) {
        ArrayList<PlayerBan> banList = new ArrayList<PlayerBan>();
        for (PlayerBan ban : bans) {
            if (ban.startTime >= time) {
                banList.add(ban);
            }
        }
        return banList;
    }

    public boolean isBanned() {
        for (PlayerBan ban : bans) {
            if (!ban.forgiven && ban.endTime > System.currentTimeMillis()) {
                return true;
            }
        }
        return false;
    }

    public boolean isBannedByBot() {
        for (PlayerBan ban : bans) {
            if (!ban.forgiven && ban.endTime > System.currentTimeMillis() && (ban.reason == PlayerBan.BanReason.NOSHOW || ban.reason == PlayerBan.BanReason.RAGEQUIT)) {
                return true;
            }
        }
        return false;
    }

    public PlayerRank getRank() {
        return PlayerRank.getRankByElo(elo);
    }

    public boolean didChangeRank() {
        PlayerRank currentRank = PlayerRank.getRankByElo(elo);
        PlayerRank previousRank = PlayerRank.getRankByElo(elo - eloChange);

        // Update roles
        // TODO make it work for different servers
//        if (currentRank != previousRank) {
//            user.removeRoleById(previousRank.getRoleId());
//        }
//        if (user.hasRoleById(PlayerRank.LEET.getRoleId()) && elorank > 5) {
//            user.removeRoleById(PlayerRank.LEET.getRoleId());
//        }
//        if (!user.hasRoleById(PlayerRank.LEET.getRoleId()) && elorank <= 5) {
//            user.addRoleById(PlayerRank.LEET.getRoleId());
//        }
//        if (!user.hasRoleById(currentRank.getRoleId())) {
//            user.addRoleById(currentRank.getRoleId());
//        }

        return currentRank != previousRank;
    }

    private static List<Player> playerList = new ArrayList<Player>();

    private record Identity(String discordId, String auth) {}

    // Guarded by Player.class. Only lifecycle changes advance the sequence; loading
    // an unrelated identity cannot invalidate a successful in-flight load.
    private static long lifecycleSequence;
    private static final Map<Identity, Long> invalidatedAt = new HashMap<>();
    // Guarded by Player.class. Prevent parallel commands from hydrating the same player repeatedly.
    private static final Map<String, CompletableFuture<Player>> loadingByDiscordId = new HashMap<>();

    private Identity identity() {
        return new Identity(user.getId(), urtauth);
    }

    static synchronized long beginLoad() {
        return lifecycleSequence;
    }

    /** Atomically canonicalize a fully hydrated snapshot, or reject an obsolete one. */
    static synchronized Player publishLoaded(Player loaded, long startedAt, boolean onlyActive) {
        Identity identity = loaded.identity();
        // Prefer the newest public registration, retaining the constructor's
        // historical behavior of allowing duplicate entries.
        for (int i = playerList.size() - 1; i >= 0; i--) {
            Player cached = playerList.get(i);
            if (identity.equals(cached.identity())) {
                return !onlyActive || cached.getActive() ? cached : null;
            }
        }
        if (invalidatedAt.getOrDefault(identity, 0L) > startedAt
                || (onlyActive && !loaded.getActive())) {
            return null;
        }
        playerList.add(loaded);
        return loaded;
    }

    public static Player get(String urtauth) {
        synchronized (Player.class) {
            for (Player player : playerList) {
                if (player.getUrtauth().equals(urtauth) && player.getActive())
                    return player;
            }
        }
        return db.loadPlayer(urtauth);
    }

    public static Player get(DiscordUser user) {
        CompletableFuture<Player> pending;
        boolean loadHere = false;
        synchronized (Player.class) {
            for (Player player : playerList) {
                if (player.getDiscordUser().getId().equals(user.getId()) && player.getActive())
                    return player;
            }
            pending = loadingByDiscordId.get(user.getId());
            if (pending == null) {
                pending = new CompletableFuture<>();
                loadingByDiscordId.put(user.getId(), pending);
                loadHere = true;
            }
        }
        if (!loadHere) {
            return pending.join();
        }
        try {
            Player loaded = db.loadPlayer(user);
            pending.complete(loaded);
            return loaded;
        } catch (Throwable failure) {
            pending.completeExceptionally(failure);
            throw failure;
        } finally {
            synchronized (Player.class) {
                loadingByDiscordId.remove(user.getId(), pending);
            }
        }
    }

    /** Message formatting must not hydrate players or fetch Discord members on the queue worker. */
    static synchronized Player getCachedByDiscordId(String discordId) {
        for (Player player : playerList) {
            if (player.getActive() && player.getDiscordUser().getId().equals(discordId)) {
                return player;
            }
        }
        return null;
    }

    public static Player get(DiscordUser user, String urtauth) {
        synchronized (Player.class) {
            for (Player player : playerList) {
                if (player.getUrtauth().equals(urtauth) && player.getDiscordUser().getId().equals(user.getId()))
                    return player;
            }
        }
        return db.loadPlayer(user, urtauth, false);
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof Player) {
            Player player = (Player) o;
            return player.getDiscordUser().equals(this.getDiscordUser()) && player.urtauth == this.urtauth;
        }
        return false;
    }

    @Override
    public String toString() {
        return this.urtauth;
    }

    public Region getRegion() {
        if (this.country.equalsIgnoreCase("NOT_DEFINED")) {
            return Region.WORLD;
        } else {
            String continent = Country.getContinent(this.country);

            return Region.valueOf(continent);
        }
    }

    public String getCountry() {
        return this.country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public static synchronized void remove(Player player) {
        Identity identity = player.identity();
        invalidatedAt.put(identity, ++lifecycleSequence);
        player.setActive(false);
        playerList.removeIf(candidate -> {
            if (!identity.equals(candidate.identity())) {
                return false;
            }
            candidate.setActive(false);
            return true;
        });
    }

    public boolean getEnforceAC() {
        return this.enforceAC;
    }

    public void setEnforceAC(boolean enforceAC) {
        this.enforceAC = enforceAC;
    }

    public boolean getProctf() {
        return this.proctf;
    }

    public void setProctf(boolean proctf) {
        this.proctf = proctf;
    }

    public float getCaptainScore(Gametype gt) {
        WinDrawLoss wdl = stats.ts_wdl;
        float kdr = stats.kdr;
        if (gt.getName().equals("CTF")) {
            wdl = stats.ctf_wdl;
            kdr = stats.ctf_rating;
        }
        if (wdl.getTotal() < 5) {
            return (float) elo;
        }
        return (float) (elo + (kdr * 500 + wdl.calcWinRatio() * 500.0) / 4);
    }

    public float getCaptainScore(Gametype gt, Float ftwglRating, boolean useFtwOnly) {
        // Use FTWGL rating if available (higher is better)
        if (ftwglRating != null && ftwglRating > 0) {
            return ftwglRating;
        }

        // If we're in FTW-only mode, unrated players score 0 so they sort below rated players
        if (useFtwOnly) {
            return 0;
        }
        
        // Fallback to existing ELO/KDR/WDL blended score
        return getCaptainScore(gt);
    }

    public void setRank(int rank) {
        this.elorank = rank;
    }

    public int getEloRank() {
        return db.getRankForPlayer(this);
    }

    public synchronized long getCoins() {
        return coins;
    }

    public synchronized void setCoins(long coins) {
        this.coins = coins;
        unsavedCoinDelta = 0;
    }

    public synchronized void addCoins(long amount) {
        coins = Math.addExact(coins, amount);
        unsavedCoinDelta = Math.addExact(unsavedCoinDelta, amount);
    }

    public synchronized void spendCoins(long amount) {
        coins = Math.subtractExact(coins, amount);
        unsavedCoinDelta = Math.subtractExact(unsavedCoinDelta, amount);
    }

    public synchronized void saveWallet() {
        if (unsavedCoinDelta != 0) {
            long current = db.updatePlayerCoins(this, unsavedCoinDelta);
            unsavedCoinDelta = 0;
            coins = current;
        }
    }

    public synchronized void refreshWallet() {
        long balance = db.walletBalance(this);
        coins = Math.addExact(balance, unsavedCoinDelta);
    }

    public long getEloBoost() {
        return eloBoost;
    }

    public void setEloBoost(long eloBoost) {
        this.eloBoost = eloBoost;
        db.updatePlayerBoost(this);
    }

    // Loading stored values is not a wallet mutation and must never write them back.
    void hydrateBoost(long eloBoost, int mapVotes, int mapBans) {
        this.eloBoost = eloBoost;
        this.additionalMapVotes = mapVotes;
        this.mapBans = mapBans;
    }

    public boolean hasBoostActive() {
        return eloBoost >= System.currentTimeMillis();
    }

    public int getAdditionalMapVotes() {
        return additionalMapVotes;
    }

    public void setAdditionalMapVotes(int mapVotes) {
        this.additionalMapVotes = mapVotes;
        db.updatePlayerBoost(this);
    }

    public int getMapBans() {
        return mapBans;
    }

    public void setMapBans(int mapBans) {
        this.mapBans = mapBans;
        db.updatePlayerBoost(this);
    }

    public void saveSpree(Gametype gametype, boolean won) {
        if (this.spree.containsKey(gametype)) {
            if (won) {
                this.spree.put(gametype, this.spree.get(gametype) > 0 ? this.spree.get(gametype) + 1 : 1);
            } else {
                this.spree.put(gametype, this.spree.get(gametype) < 0 ? this.spree.get(gametype) - 1 : -1);
            }
            db.updateSpree(this, gametype, this.spree.get(gametype));
        } else {
            this.spree.put(gametype, won ? 1 : -1);
            db.createSpree(this, gametype, this.spree.get(gametype));
        }
    }
}
