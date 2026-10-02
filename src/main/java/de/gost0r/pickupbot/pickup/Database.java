package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.discord.DiscordChannel;
import de.gost0r.pickupbot.discord.DiscordRole;
import de.gost0r.pickupbot.discord.DiscordService;
import de.gost0r.pickupbot.discord.DiscordUser;
import de.gost0r.pickupbot.permission.PermissionService;
import de.gost0r.pickupbot.pickup.PlayerBan.BanReason;
import de.gost0r.pickupbot.pickup.server.Server;
import de.gost0r.pickupbot.pickup.stats.WinDrawLoss;
import lombok.extern.slf4j.Slf4j;

import java.sql.*;
import java.util.*;

@Slf4j
public class Database {

    private final PickupLogic logic;
    private final DiscordService discordService;
    private final PermissionService permissionService;

    private Connection c = null;
    // Match persistence uses a separate WAL writer so a transaction cannot include
    // unrelated queries on the shared command connection.
    private Connection matchWrites;
    private Map<String, PreparedStatement> preparedStmtCache;
    private Map<RankingKey, Map<String, Integer>> rankingCache = new HashMap<>();
    private Object rankingLock = new Object();
    private long rankingCacheRevision = -1;

    private record RankingKey(int season, long start, long end, String gametype, boolean winRate) { }

    @FunctionalInterface
    private interface RankingQuery {
        Map<String, Integer> load() throws SQLException;
    }

    private int cachedRank(Player player, Gametype gt, Season season, boolean winRate,
                           RankingQuery query) throws SQLException {
        // Match results and player registration changes invalidate every affected leaderboard.
        // Historical seasons are also cleared because match results can be backfilled.
        synchronized (rankingLock == null ? this : rankingLock) {
            if (rankingCache == null) {
                rankingCache = new HashMap<>();
            }
            long revision = Player.currentSeasonStatsRevision();
            if (rankingCacheRevision != revision) {
                rankingCache.clear();
                rankingCacheRevision = revision;
            }
            RankingKey key = new RankingKey(season.number, season.startdate, season.enddate, gt.getName(), winRate);
            Map<String, Integer> ranks = rankingCache.get(key);
            if (ranks == null) {
                ranks = query.load();
                if (Player.currentSeasonStatsRevision() == revision) {
                    rankingCache.put(key, ranks);
                }
            }
            return ranks.getOrDefault(player.getUrtauth(), -1);
        }
    }

    private Map<String, Integer> readRanks(ResultSet rs) throws SQLException {
        Map<String, Integer> ranks = new HashMap<>();
        while (rs.next()) {
            ranks.put(rs.getString("auth"), rs.getInt("rowIndex"));
        }
        return ranks;
    }


    public Database(PickupLogic logic, DiscordService discordService, PermissionService permissionService) {
        this.discordService = discordService;
        this.permissionService = permissionService;
        preparedStmtCache = new HashMap<>();
        this.logic = logic;
        initConnection();
    }

    private void initConnection() {
        try {
            c = DriverManager.getConnection("jdbc:sqlite:" + logic.bot.env + ".pickup.db");
            try (Statement stmt = c.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL;");
                stmt.execute("PRAGMA busy_timeout=5000;");
            }
            initTable();
            ensureMatchWriter();
            // Seed planner statistics after index creation. Without sqlite_stat1,
            // SQLite can scan every player's history before filtering to this season.
            try (Statement stmt = c.createStatement()) {
                stmt.execute("PRAGMA optimize=0x10002;");
            }
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
    }

    public synchronized void disconnect() {
        try {
            if (matchWrites != null) {
                matchWrites.close();
            }
            // Checkpoint WAL to flush all pending writes to the main database file
            try (Statement stmt = c.createStatement()) {
                stmt.execute("PRAGMA wal_checkpoint(TRUNCATE);");
            }
            log.info("WAL checkpoint completed, closing database connection");
            c.close();
        } catch (SQLException e) {
            log.warn("Exception during database shutdown: ", e);
        }
    }

    public void optimize() {
        try (Statement stmt = c.createStatement()) {
            stmt.execute("PRAGMA optimize;");
        } catch (SQLException e) {
            log.warn("Unable to update SQLite planner statistics", e);
        }
    }

    public PreparedStatement getPreparedStatement(String sql) throws SQLException {
        PreparedStatement stmt = preparedStmtCache.get(sql);
        if (stmt == null) {
            stmt = c.prepareStatement(sql);
            preparedStmtCache.put(sql, stmt);
        }
        return stmt;
    }

    private void initTable() {
        try {
            Statement stmt = c.createStatement();
            String sql = "CREATE TABLE IF NOT EXISTS player ( userid TEXT,"
                    + "urtauth TEXT,"
                    + "elo INTEGER DEFAULT 1000,"
                    + "elochange INTEGER DEFAULT 0,"
                    + "active TEXT,"
                    + "country TEXT,"
                    + "enforce_ac TEXT DEFAULT 'true',"
                    + "coins INTEGER DEFAULT 1000,"
                    + "eloboost INTEGER DEFAULT 0,"
                    + "mapvote INTEGER DEFAULT 0,"
                    + "mapban INTEGER DEFAULT 0,"
                    + "proctf TEXT DEFAULT 'true',"
                    + "PRIMARY KEY (userid, urtauth) )";
            stmt.executeUpdate(sql);

            sql = "CREATE TABLE IF NOT EXISTS gametype ( gametype TEXT PRIMARY KEY,"
                    + "teamsize INTEGER, "
                    + "active TEXT,"
                    + "recent_map_exclude INTEGER DEFAULT 2 )";
            stmt.executeUpdate(sql);

            sql = "CREATE TABLE IF NOT EXISTS map ( map TEXT,"
                    + "gametype TEXT,"
                    + "active TEXT,"
                    + "banned_until INTEGER DEFAULT 0,"
                    + "FOREIGN KEY (gametype) REFERENCES gametype(gametype),"
                    + "PRIMARY KEY (map, gametype) )";
            stmt.executeUpdate(sql);

            sql = "CREATE TABLE IF NOT EXISTS banlist ( ID INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "player_userid TEXT,"
                    + "player_urtauth TEXT,"
                    + "reason TEXT,"
                    + "start INTEGER,"
                    + "end INTEGER,"
                    + "pardon TEXT,"
                    + "forgiven BOOLEAN,"
                    + "FOREIGN KEY (player_userid, player_urtauth) REFERENCES player(userid, urtauth) )";
            stmt.executeUpdate(sql);

            sql = "CREATE TABLE IF NOT EXISTS report ( ID INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "player_userid TEXT,"
                    + "player_urtauth TEXT,"
                    + "reporter_userid TEXT,"
                    + "reporter_urtauth TEXT,"
                    + "reason TEXT,"
                    + "match INTEGER,"
                    + "FOREIGN KEY (player_userid, player_urtauth) REFERENCES player(userid, urtauth),"
                    + "FOREIGN KEY (reporter_userid, reporter_urtauth) REFERENCES player(userid, urtauth),"
                    + "FOREIGN KEY (match) REFERENCES match(ID) )";
            stmt.executeUpdate(sql);

            sql = "CREATE TABLE IF NOT EXISTS match ( ID INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "server INTEGER,"
                    + "gametype TEXT,"
                    + "state TEXT,"
                    + "starttime INTEGER,"
                    + "map TEXT,"
                    + "elo_red INTEGER,"
                    + "elo_blue INTEGER,"
                    + "score_red INTEGER DEFAULT 0,"
                    + "score_blue INTEGER DEFAULT 0,"
                    + "FOREIGN KEY (server) REFERENCES server(id),"
                    + "FOREIGN KEY (map, gametype) REFERENCES map(map, gametype),"
                    + "FOREIGN KEY (gametype) REFERENCES gametype(gametype) )";
            stmt.executeUpdate(sql);

            sql = "CREATE INDEX IF NOT EXISTS idx_match_gametype_state ON match (gametype, state, ID)";
            stmt.executeUpdate(sql);

            sql = "CREATE TABLE IF NOT EXISTS player_in_match ( ID INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "matchid INTEGER,"
                    + "player_userid TEXT,"
                    + "player_urtauth TEXT,"
                    + "team TEXT,"
                    + "FOREIGN KEY (matchid) REFERENCES match(ID), "
                    + "FOREIGN KEY (player_userid, player_urtauth) REFERENCES player(userid, urtauth) )";
            stmt.executeUpdate(sql);

            sql = "CREATE TABLE IF NOT EXISTS score ( ID INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "kills INTEGER DEFAULT 0,"
                    + "deaths INTEGER DEFAULT 0,"
                    + "assists INTEGER DEFAULT 0,"
                    + "caps INTEGER DEFAULT 0,"
                    + "returns INTEGER DEFAULT 0,"
                    + "fckills INTEGER DEFAULT 0,"
                    + "stopcaps INTEGER DEFAULT 0,"
                    + "protflag INTEGER DEFAULT 0 )";
            stmt.executeUpdate(sql);

            sql = "CREATE TABLE IF NOT EXISTS stats ( pim INTEGER PRIMARY KEY,"
                    + "ip TEXT,"
                    + "status TEXT,"
                    + "score_1 INTEGER,"
                    + "score_2 INTEGER,"
                    + "FOREIGN KEY(pim) REFERENCES player_in_match(ID),"
                    + "FOREIGN KEY (score_1) REFERENCES score(ID),"
                    + "FOREIGN KEY (score_2) REFERENCES score(ID) )";
            stmt.executeUpdate(sql);

            sql = "CREATE TABLE IF NOT EXISTS server ( ID INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "ip TEXT,"
                    + "port INTEGER,"
                    + "rcon TEXT,"
                    + "password TEXT,"
                    + "active TEXT,"
                    + "region TEXT)";
            stmt.executeUpdate(sql);

            sql = "CREATE TABLE IF NOT EXISTS roles (role TEXT,"
                    + "type TEXT,"
                    + "PRIMARY KEY (role) )";
            stmt.executeUpdate(sql);

            sql = "CREATE TABLE IF NOT EXISTS channels (channel TEXT,"
                    + "type TEXT,"
                    + "PRIMARY KEY (channel, type) )";
            stmt.executeUpdate(sql);

            sql = "CREATE TABLE IF NOT EXISTS season (number INTEGER,"
                    + "startdate INTEGER,"
                    + "enddate INTEGER,"
                    + "PRIMARY KEY (number) )";
            stmt.executeUpdate(sql);

            migrateSeasonGameNumbers();

            sql = "CREATE TABLE IF NOT EXISTS bets (ID INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "player_userid TEXT,"
                    + "player_urtauth TEXT,"
                    + "matchid INTEGER,"
                    + "team INTEGER," // red = 0   blue = 1
                    + "won TEXT,"
                    + "amount INTEGER,"
                    + "odds FLOAT,"
                    + "open INTEGER DEFAULT 0,"
                    + "FOREIGN KEY (matchid) REFERENCES match(ID), "
                    + "FOREIGN KEY (player_userid, player_urtauth) REFERENCES player(userid, urtauth) )";
            stmt.executeUpdate(sql);

            if (!columnExists("bets", "open")) {
                stmt.executeUpdate("ALTER TABLE bets ADD COLUMN open INTEGER DEFAULT 0");
            }

            // Only matches created with durable bet escrow are eligible for automatic recovery.
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS match_settlement (matchid INTEGER PRIMARY KEY, settled INTEGER NOT NULL DEFAULT 0, teamsize INTEGER NOT NULL)");
            if (!columnExists("match_settlement", "teamsize")) {
                // Older markers did not record the original size. Only infer a
                // positive team mode when both teams were recorded and its catalog
                // entry still exists. Unknown/ambiguous matches require review.
                stmt.executeUpdate("ALTER TABLE match_settlement ADD COLUMN teamsize INTEGER");
                stmt.executeUpdate("UPDATE match_settlement SET teamsize=(SELECT g.teamsize FROM match m "
                        + "JOIN gametype g ON g.gametype=m.gametype WHERE m.ID=match_settlement.matchid "
                        + "AND g.teamsize>0 "
                        + "AND EXISTS(SELECT 1 FROM player_in_match p WHERE p.matchid=m.ID AND p.team='red') "
                        + "AND EXISTS(SELECT 1 FROM player_in_match p WHERE p.matchid=m.ID AND p.team='blue'))");
            }
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_bets_open_match ON bets (matchid, open)");

            sql = "CREATE TABLE IF NOT EXISTS spree (ID INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "player_userid TEXT,"
                    + "player_urtauth TEXT,"
                    + "gametype TEXT,"
                    + "spree INTEGER DEFAULT 0,"
                    + "personal_best INTEGER DEFAULT 0,"
                    + "personal_worst INTEGER DEFAULT 0,"
                    + "FOREIGN KEY (gametype) REFERENCES gametype(gametype), "
                    + "FOREIGN KEY (player_userid, player_urtauth) REFERENCES player(userid, urtauth) )";
            stmt.executeUpdate(sql);

            sql = "CREATE INDEX IF NOT EXISTS idx_pim_matchid ON player_in_match (matchid)";
            stmt.executeUpdate(sql);

            sql = "CREATE INDEX IF NOT EXISTS idx_pim_urtauth_id ON player_in_match (player_urtauth, ID DESC)";
            stmt.executeUpdate(sql);

            sql = "CREATE INDEX IF NOT EXISTS idx_match_start ON match (starttime)";
            stmt.executeUpdate(sql);

            sql = "CREATE INDEX IF NOT EXISTS idx_player_active_elo ON player (active, elo DESC)";
            stmt.executeUpdate(sql);

            sql = "CREATE INDEX IF NOT EXISTS idx_banlist_urtauth ON banlist (player_urtauth)";
            stmt.executeUpdate(sql);

            sql = "CREATE INDEX IF NOT EXISTS idx_bets_urtauth ON bets (player_urtauth, ID DESC)";
            stmt.executeUpdate(sql);

            sql = "CREATE INDEX IF NOT EXISTS idx_spree_urtauth_gametype ON spree (player_urtauth, gametype)";
            stmt.executeUpdate(sql);

            sql = "CREATE INDEX IF NOT EXISTS idx_player_urtauth ON player (urtauth)";
            stmt.executeUpdate(sql);

            stmt.close();

            // Migrations: add columns that may not exist in older databases
            if (!columnExists("gametype", "recent_map_exclude")) {
                Statement mig = c.createStatement();
                mig.executeUpdate("ALTER TABLE gametype ADD COLUMN recent_map_exclude INTEGER DEFAULT 2");
                mig.close();
            }

        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
    }

    private void migrateSeasonGameNumbers() throws SQLException {
        boolean addSeason = !columnExists("match", "season_number");
        boolean addGameNumber = !columnExists("match", "season_game_number");
        if (!addSeason && !addGameNumber) return;

        // Schema and historical numbering commit together so a failed migration can
        // be retried. Later startups neither scan history nor renumber existing games.
        c.setAutoCommit(false);
        try (Statement stmt = c.createStatement()) {
            if (addSeason) stmt.executeUpdate("ALTER TABLE match ADD COLUMN season_number INTEGER");
            if (addGameNumber) stmt.executeUpdate("ALTER TABLE match ADD COLUMN season_game_number INTEGER");
            stmt.executeUpdate("WITH seasons AS (SELECT m.ID, m.gametype, "
                    + "(SELECT s.number FROM season s WHERE m.starttime >= s.startdate "
                    + "AND m.starttime < s.enddate ORDER BY s.number DESC LIMIT 1) AS season_number FROM match m), "
                    + "numbered AS (SELECT ID, season_number, "
                    + "ROW_NUMBER() OVER (PARTITION BY season_number, gametype ORDER BY ID) AS game_number "
                    + "FROM seasons WHERE season_number IS NOT NULL) "
                    + "UPDATE match SET season_number=n.season_number, season_game_number=n.game_number "
                    + "FROM numbered n WHERE match.ID=n.ID AND match.season_number IS NULL");
            stmt.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS idx_match_season_game_number "
                    + "ON match (season_number, gametype, season_game_number)");
            c.commit();
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(true);
        }
    }


    public void createPlayer(Player player) {
        try {
            // check whether user exists
            String sql = "SELECT * FROM player WHERE userid=? AND urtauth=?";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, player.getDiscordUser().getId());
            pstmt.setString(2, player.getUrtauth());
            ResultSet rs = pstmt.executeQuery();
            if (!rs.next()) {
                sql = "INSERT INTO player (userid, urtauth, elo, elochange, active, country) VALUES (?, ?, ?, ?, ?, ?)";
                pstmt = c.prepareStatement(sql);
                pstmt.setString(1, player.getDiscordUser().getId());
                pstmt.setString(2, player.getUrtauth());
                pstmt.setInt(3, player.getElo());
                pstmt.setInt(4, player.getEloChange());
                pstmt.setString(5, String.valueOf(true));
                pstmt.setString(6, player.getCountry());
                pstmt.executeUpdate();
            } else {
                sql = "UPDATE player SET active=? WHERE userid=? AND urtauth=?";
                pstmt = c.prepareStatement(sql);
                pstmt.setString(1, String.valueOf(true));
                pstmt.setString(2, player.getDiscordUser().getId());
                pstmt.setString(3, player.getUrtauth());
                pstmt.executeUpdate();
            }
            rs.close();
            pstmt.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
    }

    public void createBan(PlayerBan ban) {
        try {
            String sql = "INSERT INTO banlist (player_userid, player_urtauth, start, end, reason, pardon, forgiven) VALUES (?, ?, ?, ?, ?, 'null', 0)";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, ban.player.getDiscordUser().getId());
            pstmt.setString(2, ban.player.getUrtauth());
            pstmt.setLong(3, ban.startTime);
            pstmt.setLong(4, ban.endTime);
            pstmt.setString(5, ban.reason.name());
            pstmt.executeUpdate();
            pstmt.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
    }

    public void forgiveBan(Player player) {
        try {
            String sql = "UPDATE banlist SET forgiven = 1 WHERE player_urtauth = ? AND end > ?";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, player.getUrtauth());
            pstmt.setLong(2, System.currentTimeMillis());
            pstmt.executeUpdate();
            pstmt.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
    }

    public void forgiveBotBan(Player player) {
        try {
            String sql = "UPDATE banlist SET forgiven = 1 WHERE player_urtauth = ? AND end > ? AND (reason = 'RAGEQUIT' OR reason = 'NOSHOW')";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, player.getUrtauth());
            pstmt.setLong(2, System.currentTimeMillis());
            pstmt.executeUpdate();
            pstmt.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
    }

    public void createServer(Server server) {
        try {
            String sql = "INSERT INTO server (ip, port, rcon, password, active, region) VALUES (?, ?, ?, ?, ?, ?)";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, server.IP);
            pstmt.setInt(2, server.port);
            pstmt.setString(3, server.rconpassword);
            pstmt.setString(4, server.password);
            pstmt.setString(5, String.valueOf(server.active));
            pstmt.setString(6, server.region.toString());
            pstmt.executeUpdate();
            pstmt.close();
            Statement stmt = c.createStatement();
            sql = "SELECT ID FROM server ORDER BY ID DESC";
            ResultSet rs = stmt.executeQuery(sql);
            rs.next();
            server.id = rs.getInt("ID");
            stmt.close();
            rs.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
    }

    public void createMap(GameMap map, Gametype gametype) {
        try {
            String sql = "INSERT INTO map (map, gametype, active) VALUES (?, ?, ?)";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, map.name);
            pstmt.setString(2, gametype.getName());
            pstmt.setString(3, String.valueOf(map.isActiveForGametype(gametype)));
            pstmt.executeUpdate();
            pstmt.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
    }

    @FunctionalInterface
    private interface MatchWrite<T> {
        T execute() throws SQLException;
    }

    private void ensureMatchWriter() throws SQLException {
        if (matchWrites != null && !matchWrites.isClosed()) return;
        Connection writer = DriverManager.getConnection("jdbc:sqlite:" + logic.bot.env + ".pickup.db");
        try (Statement stmt = writer.createStatement()) {
            stmt.execute("PRAGMA busy_timeout=1000;");
        } catch (SQLException failure) {
            try {
                writer.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
        matchWrites = writer;
    }

    private <T> T writeMatch(String operation, MatchWrite<T> write) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            boolean transactionStarted = false;
            boolean committed = false;
            try {
                ensureMatchWriter();
                matchWrites.setAutoCommit(false);
                transactionStarted = true;
                T result = write.execute();
                matchWrites.commit();
                committed = true;
                matchWrites.setAutoCommit(true);
                return result;
            } catch (SQLException | RuntimeException failure) {
                if (committed) {
                    // Retrying after a successful commit could duplicate a newly created match.
                    throw new MatchPersistenceException("Committed " + operation + " but could not reset the writer", failure);
                }
                if (transactionStarted) {
                    try {
                        matchWrites.rollback();
                        matchWrites.setAutoCommit(true);
                    } catch (SQLException rollbackFailure) {
                        failure.addSuppressed(rollbackFailure);
                        // A failed rollback leaves the connection unsafe for another transaction.
                        try {
                            matchWrites.close();
                        } catch (SQLException closeFailure) {
                            failure.addSuppressed(closeFailure);
                        }
                        matchWrites = null;
                        throw new MatchPersistenceException("Unable to " + operation + ": rollback failed", failure);
                    }
                }
                if (failure instanceof SQLException sql
                        && ((sql.getErrorCode() & 0xff) == 5 || (sql.getErrorCode() & 0xff) == 6)
                        && attempt < 3) {
                    log.warn("SQLite busy during {} (attempt {}/3); retrying", operation, attempt);
                    try {
                        Thread.sleep(100L * attempt);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new MatchPersistenceException("Interrupted while retrying " + operation, interrupted);
                    }
                    continue;
                }
                throw new MatchPersistenceException("Unable to " + operation + " after " + attempt + " attempt(s)", failure);
            }
        }
        throw new IllegalStateException("Unreachable match write retry state");
    }

    private record CreatedMatch(int id, SeasonGameNumber number) { }

    public synchronized int createMatch(Match match) {
        CreatedMatch created = writeMatch("create match", () -> {
                int id;
                SeasonGameNumber number = null;
                try (PreparedStatement insertMatch = matchWrites.prepareStatement(
                        "INSERT INTO match (state, gametype, server, starttime, map, elo_red, elo_blue) VALUES (?, ?, ?, ?, ?, ?, ?)");
                     PreparedStatement insertScore = matchWrites.prepareStatement("INSERT INTO score (kills, deaths) VALUES (0, 0)");
                     PreparedStatement insertPlayer = matchWrites.prepareStatement(
                             "INSERT INTO player_in_match (matchid, player_userid, player_urtauth, team) VALUES (?, ?, ?, ?)");
                     PreparedStatement insertStats = matchWrites.prepareStatement(
                             "INSERT INTO stats (pim, ip, score_1, score_2, status) VALUES (?, null, ?, ?, ?)")) {
                    insertMatch.setString(1, match.getMatchState().name());
                    insertMatch.setString(2, match.getGametype().getName());
                    insertMatch.setInt(3, match.getServer().id);
                    insertMatch.setLong(4, match.getStartTime());
                    insertMatch.setString(5, match.getMap().name);
                    insertMatch.setInt(6, match.getEloRed());
                    insertMatch.setInt(7, match.getEloBlue());
                    insertMatch.executeUpdate();
                    id = lastInsertId(matchWrites);

                    try (PreparedStatement numbering = matchWrites.prepareStatement(
                            "UPDATE match SET season_number=?, season_game_number=? WHERE ID=?");
                         PreparedStatement next = matchWrites.prepareStatement(
                                 "SELECT s.number, COALESCE((SELECT MAX(m.season_game_number) FROM match m "
                                         + "WHERE m.season_number=s.number AND m.gametype=?), 0)+1 "
                                         + "FROM season s WHERE ? >= s.startdate AND ? < s.enddate "
                                         + "ORDER BY s.number DESC LIMIT 1")) {
                        next.setString(1, match.getGametype().getName());
                        next.setLong(2, match.getStartTime());
                        next.setLong(3, match.getStartTime());
                        try (ResultSet row = next.executeQuery()) {
                            if (row.next()) {
                                number = new SeasonGameNumber(row.getInt(1), row.getInt(2));
                                numbering.setInt(1, number.season());
                                numbering.setInt(2, number.gameNumber());
                                numbering.setInt(3, id);
                                numbering.executeUpdate();
                            }
                        }
                    }

                    try (PreparedStatement pending = matchWrites.prepareStatement(
                            "INSERT INTO match_settlement (matchid, teamsize) VALUES (?, ?)")) {
                        pending.setInt(1, id);
                        pending.setInt(2, match.getGametype().getTeamSize());
                        pending.executeUpdate();
                    }

                    for (Player player : match.getPlayerList()) {
                        int[] scores = new int[2];
                        for (int i = 0; i < scores.length; i++) {
                            insertScore.executeUpdate();
                            scores[i] = lastInsertId(matchWrites);
                        }
                        insertPlayer.setInt(1, id);
                        insertPlayer.setString(2, player.getDiscordUser().getId());
                        insertPlayer.setString(3, player.getUrtauth());
                        insertPlayer.setString(4, match.getTeam(player));
                        insertPlayer.executeUpdate();
                        insertStats.setInt(1, lastInsertId(matchWrites));
                        insertStats.setInt(2, scores[0]);
                        insertStats.setInt(3, scores[1]);
                        insertStats.setString(4, match.getStats(player).getStatus().name());
                        insertStats.executeUpdate();
                    }
                }
                return new CreatedMatch(id, number);
        });
        match.setSeasonGameNumber(created.number());
        return created.id();
    }

    private static int lastInsertId(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery("SELECT last_insert_rowid()")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    public int getLastMatchID() {
        try {
            String sql = "SELECT MAX(ID) FROM match";
            PreparedStatement pstmt = getPreparedStatement(sql);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                int id = rs.getInt(1);
                rs.close();
                return id;
            }
            rs.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
        return -1;
    }

    public int getNumberOfGames(Player player) {
        try {
            String sql = "SELECT COUNT(player_urtauth) as count FROM player_in_match WHERE player_urtauth = ?";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, player.getUrtauth());
            ResultSet rs = pstmt.executeQuery();
            rs.next();
            int count = rs.getInt("count");
            rs.close();
            pstmt.close();
            return count;
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
        return -1;
    }


    // LOADING

    public Map<PickupRoleType, List<DiscordRole>> loadRoles() {
        Map<PickupRoleType, List<DiscordRole>> map = new HashMap<PickupRoleType, List<DiscordRole>>();
        try {
            String sql = "SELECT role, type FROM roles";
            Statement stmt = c.createStatement();
            ResultSet rs = stmt.executeQuery(sql);
            while (rs.next()) {
                PickupRoleType type = PickupRoleType.valueOf(rs.getString("type"));
                if (type == PickupRoleType.NONE) continue;
                if (!map.containsKey(type)) {
                    map.put(type, new ArrayList<DiscordRole>());
                }
                DiscordRole role = discordService.getRoleById(rs.getString("role"));
                assert role != null;
                log.debug("loadRoles(): {} type={}", role.getId(), type.name());
                map.get(type).add(role);
            }

            stmt.close();
            rs.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
        return map;
    }

    public Map<PickupChannelType, List<DiscordChannel>> loadChannels() {
        Map<PickupChannelType, List<DiscordChannel>> map = new HashMap<PickupChannelType, List<DiscordChannel>>();
        try {
            String sql = "SELECT channel, type FROM channels";
            Statement stmt = c.createStatement();
            ResultSet rs = stmt.executeQuery(sql);
            while (rs.next()) {
                PickupChannelType type = PickupChannelType.valueOf(rs.getString("type"));
                if (type == PickupChannelType.NONE) continue;
                if (!map.containsKey(type)) {
                    map.put(type, new ArrayList<DiscordChannel>());
                }
                DiscordChannel channel = discordService.getChannelById(rs.getString("channel"));
                assert channel != null;
                map.get(type).add(channel);
                log.debug("loadChannels(): {} name={} type={}", channel.getId(), channel.getName(), type.name());
            }

            stmt.close();
            rs.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
        return map;
    }


    public List<Server> loadServers() {
        List<Server> serverList = new ArrayList<Server>();
        try {
            Statement stmt = c.createStatement();
            String sql = "SELECT id, ip, port, rcon, password, active, region FROM server";
            ResultSet rs = stmt.executeQuery(sql);
            while (rs.next()) {
                int id = rs.getInt("id");
                String ip = rs.getString("ip");
                int port = rs.getInt("port");
                String rcon = rs.getString("rcon");
                String password = rs.getString("password");
                boolean active = Boolean.parseBoolean(rs.getString("active"));
                String str_region = rs.getString("region");

                Server server = new Server(id, ip, port, rcon, password, active, Region.valueOf(str_region));
                serverList.add(server);
            }
            stmt.close();
            rs.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
        return serverList;
    }


    public List<Gametype> loadGametypes() {
        List<Gametype> gametypeList = new ArrayList<Gametype>();
        try {
            Statement stmt = c.createStatement();
            String sql = "SELECT gametype, teamsize, active, recent_map_exclude FROM gametype";
            ResultSet rs = stmt.executeQuery(sql);
            while (rs.next()) {
                Gametype gametype = new Gametype(rs.getString("gametype"), rs.getInt("teamsize"), Boolean.parseBoolean(rs.getString("active")), false, rs.getInt("recent_map_exclude"));
                log.debug("{} active={}", gametype.getName(), gametype.getActive());
                gametypeList.add(gametype);
            }
            stmt.close();
            rs.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
        return gametypeList;
    }


    public List<GameMap> loadMaps() {
        List<GameMap> maplist = new ArrayList<GameMap>();
        try {
            Statement stmt = c.createStatement();
            String sql = "SELECT map, gametype, active, banned_until FROM map";
            ResultSet rs = stmt.executeQuery(sql);
            while (rs.next()) {
                GameMap map = null;
                for (GameMap xmap : maplist) {
                    if (xmap.name.equals(rs.getString("map"))) {
                        map = xmap;
                        break;
                    }
                }
                if (map == null) {
                    map = new GameMap(rs.getString("map"));
                    map.bannedUntil = rs.getLong("banned_until");
                    maplist.add(map);
                }
                map.setGametype(logic.getGametypeByString(rs.getString("gametype")), Boolean.parseBoolean(rs.getString("active")));
                if (rs.getString("gametype").equalsIgnoreCase("TS")) {
                    map.setGametype(logic.getGametypeByString("SCRIM TS"), Boolean.parseBoolean(rs.getString("active")));
                }
                if (rs.getString("gametype").equalsIgnoreCase("CTF")) {
                    map.setGametype(logic.getGametypeByString("SCRIM CTF"), Boolean.parseBoolean(rs.getString("active")));
                }
                log.debug("{} {}={}", map.name, rs.getString("gametype"), map.isActiveForGametype(logic.getGametypeByString(rs.getString("gametype"))));
            }
            stmt.close();
            rs.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
        return maplist;
    }

    public List<Match> loadOngoingMatches() {
        List<Match> matchList = new ArrayList<Match>();
        try {
            ResultSet rs;
            String sql = "SELECT ID FROM match WHERE state=?";
            PreparedStatement pstmt = getPreparedStatement(sql);
            pstmt.setString(1, MatchState.Live.name());
            rs = pstmt.executeQuery();
            while (rs.next()) {
                Match m = loadMatch(rs.getInt("ID"));
                if (m != null) {
                    matchList.add(m);
                }
            }
            rs.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
        return matchList;
    }

    private static class PlayerMatchData {
        String userid;
        String urtauth;
        String team;
        String ip;
        MatchStats.Status status;
        Score[] scores;
        
        PlayerMatchData(String userid, String urtauth, String team, String ip, MatchStats.Status status, Score[] scores) {
            this.userid = userid;
            this.urtauth = urtauth;
            this.team = team;
            this.ip = ip;
            this.status = status;
            this.scores = scores;
        }
    }

    public Match loadMatch(int id) {
        Match match = null;
        try {
            // Single query with JOINs to fetch match + all player data in one round-trip
            String sql = "SELECT m.starttime, m.map, m.gametype, m.score_red, m.score_blue, m.elo_red, m.elo_blue, m.state, m.server,"
                    + " m.season_number, m.season_game_number,"
                    + " pim.player_userid, pim.player_urtauth, pim.team,"
                    + " st.ip, st.status,"
                    + " s1.kills AS s1_kills, s1.deaths AS s1_deaths, s1.assists AS s1_assists, s1.caps AS s1_caps, s1.returns AS s1_returns, s1.fckills AS s1_fckills, s1.stopcaps AS s1_stopcaps, s1.protflag AS s1_protflag,"
                    + " s2.kills AS s2_kills, s2.deaths AS s2_deaths, s2.assists AS s2_assists, s2.caps AS s2_caps, s2.returns AS s2_returns, s2.fckills AS s2_fckills, s2.stopcaps AS s2_stopcaps, s2.protflag AS s2_protflag"
                    + " FROM match m"
                    + " LEFT JOIN player_in_match pim ON pim.matchid = m.ID"
                    + " LEFT JOIN stats st ON st.pim = pim.ID"
                    + " LEFT JOIN score s1 ON s1.ID = st.score_1"
                    + " LEFT JOIN score s2 ON s2.ID = st.score_2"
                    + " WHERE m.ID=?";
            // Use a fresh PreparedStatement (not the cache) because the ResultSet is iterated
            // over multiple rows and concurrent calls would invalidate a cached statement's ResultSet
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setInt(1, id);
            ResultSet rs = pstmt.executeQuery();

            // Use a temporary list to collect all player data first, so we can fetch DiscordUsers concurrently
            List<PlayerMatchData> matchPlayerData = new ArrayList<>();

            // Match-level fields (read from first row)
            String matchGametype = null;
            int matchServer = 0;
            String matchMap = null;
            String matchStateStr = null;
            long matchStarttime = 0;
            int matchScoreRed = 0, matchScoreBlue = 0;
            int matchEloRed = 0, matchEloBlue = 0;
            boolean matchFound = false;
            SeasonGameNumber matchNumber = null;

            while (rs.next()) {
                if (!matchFound) {
                    matchFound = true;
                    matchStarttime = rs.getLong("starttime");
                    matchMap = rs.getString("map");
                    matchGametype = rs.getString("gametype");
                    matchScoreRed = rs.getInt("score_red");
                    matchScoreBlue = rs.getInt("score_blue");
                    matchEloRed = rs.getInt("elo_red");
                    matchEloBlue = rs.getInt("elo_blue");
                    matchStateStr = rs.getString("state");
                    matchServer = rs.getInt("server");
                    if (rs.getObject("season_number") != null) {
                        matchNumber = new SeasonGameNumber(rs.getInt("season_number"), rs.getInt("season_game_number"));
                    }
                }

                // Player data (may be null if LEFT JOIN found no players)
                String urtauth = rs.getString("player_urtauth");
                if (urtauth != null) {
                    String userid = rs.getString("player_userid");
                    String team = rs.getString("team");
                    String ip = rs.getString("ip");
                    MatchStats.Status status = MatchStats.Status.valueOf(rs.getString("status"));

                    Score[] scores = new Score[2];

                    scores[0] = new Score();
                    scores[0].score = rs.getInt("s1_kills");
                    scores[0].deaths = rs.getInt("s1_deaths");
                    scores[0].assists = rs.getInt("s1_assists");
                    scores[0].caps = rs.getInt("s1_caps");
                    scores[0].returns = rs.getInt("s1_returns");
                    scores[0].fc_kills = rs.getInt("s1_fckills");
                    scores[0].stop_caps = rs.getInt("s1_stopcaps");
                    scores[0].protect_flag = rs.getInt("s1_protflag");

                    scores[1] = new Score();
                    scores[1].score = rs.getInt("s2_kills");
                    scores[1].deaths = rs.getInt("s2_deaths");
                    scores[1].assists = rs.getInt("s2_assists");
                    scores[1].caps = rs.getInt("s2_caps");
                    scores[1].returns = rs.getInt("s2_returns");
                    scores[1].fc_kills = rs.getInt("s2_fckills");
                    scores[1].stop_caps = rs.getInt("s2_stopcaps");
                    scores[1].protect_flag = rs.getInt("s2_protflag");

                    matchPlayerData.add(new PlayerMatchData(userid, urtauth, team, ip, status, scores));
                }
            }
            rs.close();
            pstmt.close();

            if (matchFound) {
                Map<Player, MatchStats> stats = new HashMap<Player, MatchStats>();
                Map<String, List<Player>> teamList = new HashMap<String, List<Player>>();
                teamList.put("red", new ArrayList<Player>());
                teamList.put("blue", new ArrayList<Player>());

                // Concurrently fetch users from Discord and construct players
                matchPlayerData.parallelStream().forEach(pmd -> {
                    Player player = Player.get(pmd.urtauth);
                    if (player == null) {
                        player = loadPlayer(null, pmd.urtauth, false);
                    }
                    if (player != null) {
                        synchronized (stats) {
                            stats.put(player, new MatchStats(pmd.scores[0], pmd.scores[1], pmd.ip, pmd.status));
                        }
                        synchronized (teamList) {
                            teamList.get(pmd.team).add(player);
                        }
                    }
                });

                Gametype gametype = logic.getGametypeByString(matchGametype);
                if (gametype == null) {
                    // Private modes are not registered in the gametype catalog after a
                    // restart. Reconstruct live matches from the immutable snapshot.
                    try (PreparedStatement snapshot = c.prepareStatement(
                            "SELECT s.teamsize, EXISTS(SELECT 1 FROM gametype g WHERE g.gametype=?) "
                                    + "FROM match_settlement s WHERE s.matchid=?")) {
                        snapshot.setString(1, matchGametype);
                        snapshot.setInt(2, id);
                        try (ResultSet row = snapshot.executeQuery()) {
                            if (row.next() && row.getObject(1) != null && row.getInt(1) >= 0) {
                                gametype = new Gametype(matchGametype, row.getInt(1), true, row.getInt(2) == 0);
                            }
                        }
                    }
                    if (gametype == null && matchStateStr.equals(MatchState.Live.name())) {
                        throw new MatchPersistenceException("Unable to reload live match " + id + ": no original team size");
                    }
                    if (gametype == null) return null;
                }
                Server server = logic.getServerByID(matchServer);
                GameMap map = logic.getMapByName(matchMap);
                MatchState state = MatchState.valueOf(matchStateStr);

                match = new Match(id, matchStarttime,
                        map,
                        new int[]{matchScoreRed, matchScoreBlue},
                        new int[]{matchEloRed, matchEloBlue},
                        teamList,
                        state,
                        gametype,
                        server,
                        stats,
                        logic,
                        permissionService,
                        matchNumber);
                if (state == MatchState.Live) {
                    try (PreparedStatement open = c.prepareStatement(
                            "SELECT player_urtauth, team, amount, odds FROM bets WHERE matchid=? AND open=1")) {
                        open.setInt(1, id);
                        try (ResultSet betRows = open.executeQuery()) {
                            while (betRows.next()) {
                                Player bettor = Player.get(betRows.getString(1));
                                if (bettor == null) bettor = loadPlayer(null, betRows.getString(1), false);
                                if (bettor != null) match.bets.add(new Bet(id, bettor, betRows.getInt(2) == 0 ? "red" : "blue",
                                        betRows.getLong(3), betRows.getFloat(4)));
                            }
                        }
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
        return match;
    }

    public Match loadLastMatch() {
        Match match = null;
        try {
            String sql = "SELECT MAX(ID) FROM match";
            PreparedStatement pstmt = getPreparedStatement(sql);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                int id = rs.getInt(1);
                if (id > 0) {
                    match = loadMatch(id);
                }
            }
            rs.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
        return match;

    }

    public Match loadLastMatchPlayer(Player p) {
        Match match = null;
        try {
            String sql = "SELECT pim.matchid FROM player_in_match pim"
                    + " INNER JOIN match m ON m.ID = pim.matchid"
                    + " WHERE pim.player_urtauth = ? ORDER BY pim.ID DESC LIMIT 1";
            PreparedStatement pstmt = getPreparedStatement(sql);
            pstmt.setString(1, p.getUrtauth());
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                match = loadMatch(rs.getInt("matchid"));
            }
            rs.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
        return match;

    }

    /**
     * Load a lightweight match summary from a single SQL query, bypassing full Player/Match
     * object construction. Used by !last and !match where we only need display data.
     * Returns null if the match ID is invalid.
     */
    public MatchSummary loadMatchSummary(int matchId) {
        try {
            String sql = "SELECT m.starttime, m.map, m.gametype, m.score_red, m.score_blue, m.state, m.server,"
                    + " m.season_number, m.season_game_number,"
                    + " pim.player_urtauth, pim.team,"
                    + " p.country,"
                    + " (s1.kills + s2.kills) AS total_kills,"
                    + " (s1.deaths + s2.deaths) AS total_deaths,"
                    + " (s1.assists + s2.assists) AS total_assists"
                    + " FROM match m"
                    + " LEFT JOIN player_in_match pim ON pim.matchid = m.ID"
                    + " LEFT JOIN player p ON p.userid = pim.player_userid AND p.urtauth = pim.player_urtauth"
                    + " LEFT JOIN stats st ON st.pim = pim.ID"
                    + " LEFT JOIN score s1 ON s1.ID = st.score_1"
                    + " LEFT JOIN score s2 ON s2.ID = st.score_2"
                    + " WHERE m.ID=?"
                    + " ORDER BY total_kills DESC";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setInt(1, matchId);
            ResultSet rs = pstmt.executeQuery();

            MatchSummary summary = null;

            while (rs.next()) {
                if (summary == null) {
                    summary = new MatchSummary(
                            matchId,
                            rs.getLong("starttime"),
                            rs.getString("map"),
                            rs.getString("gametype"),
                            rs.getInt("score_red"),
                            rs.getInt("score_blue"),
                            rs.getString("state"),
                            rs.getInt("server"),
                            rs.getObject("season_number") == null ? null
                                    : new SeasonGameNumber(rs.getInt("season_number"), rs.getInt("season_game_number"))
                    );
                }

                String urtauth = rs.getString("player_urtauth");
                if (urtauth != null) {
                    summary.players.add(new MatchSummary.PlayerLine(
                            urtauth,
                            rs.getString("team"),
                            rs.getString("country"),
                            rs.getInt("total_kills"),
                            rs.getInt("total_deaths"),
                            rs.getInt("total_assists")
                    ));
                }
            }
            rs.close();
            pstmt.close();
            return summary;
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
        return null;
    }

    /**
     * Load the most recent match as a lightweight summary.
     */
    public MatchSummary loadLastMatchSummary() {
        try {
            String sql = "SELECT MAX(ID) FROM match";
            PreparedStatement pstmt = getPreparedStatement(sql);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                int id = rs.getInt(1);
                rs.close();
                if (id > 0) {
                    return loadMatchSummary(id);
                }
            } else {
                rs.close();
            }
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
        return null;
    }

    /**
     * Load a player's most recent match as a lightweight summary.
     */
    public MatchSummary loadLastMatchPlayerSummary(String urtauth) {
        try {
            String sql = "SELECT pim.matchid FROM player_in_match pim"
                    + " INNER JOIN match m ON m.ID = pim.matchid"
                    + " WHERE pim.player_urtauth = ? ORDER BY pim.ID DESC LIMIT 1";
            PreparedStatement pstmt = getPreparedStatement(sql);
            pstmt.setString(1, urtauth);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                int matchId = rs.getInt("matchid");
                rs.close();
                return loadMatchSummary(matchId);
            }
            rs.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
        return null;
    }

    public List<String> getRecentMapsPlayed(String gametype, int count) {
        List<String> maps = new ArrayList<>();
        try {
            String sql = "SELECT map FROM match WHERE gametype = ? "
                    + "AND state IN ('Done', 'Surrender', 'Mercy') "
                    + "ORDER BY ID DESC LIMIT ?";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, gametype);
            pstmt.setInt(2, count);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                maps.add(rs.getString("map"));
            }
            rs.close();
            pstmt.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
        return maps;
    }

    public Player loadPlayer(String urtauth) {
        return loadPlayer(null, urtauth, true);
    }

    public Player loadPlayer(DiscordUser user) {
        return loadPlayer(user, null, true);
    }

    // Can load inactive users. The snapshot stays detached through all reads and
    // resource closes; only a fully successful load may enter the shared cache.
    public Player loadPlayer(DiscordUser user, String urtauth, boolean onlyActive) {
        long startedAt = Player.beginLoad();
        Player player;
        String sql = "SELECT * FROM player WHERE userid LIKE ? AND urtauth LIKE ? AND active LIKE ?";
        try (PreparedStatement pstmt = c.prepareStatement(sql)) {
            pstmt.setString(1, user == null ? "%" : user.getId());
            pstmt.setString(2, urtauth == null ? "%" : urtauth);
            pstmt.setString(3, onlyActive ? String.valueOf(true) : "%");
            try (ResultSet rs = pstmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                DiscordUser loadedUser = user != null ? user : discordService.getUserById(rs.getString("userid"));
                player = Player.detached(loadedUser, rs.getString("urtauth"));
                player.setElo(rs.getInt("elo"));
                player.setEloChange(rs.getInt("elochange"));
                player.setActive(Boolean.parseBoolean(rs.getString("active")));
                player.setEnforceAC(Boolean.parseBoolean(rs.getString("enforce_ac")));
                player.setCountry(rs.getString("country"));
                player.setCoins(rs.getLong("coins"));
                player.hydrateBoost(rs.getLong("eloboost"), rs.getInt("mapvote"), rs.getInt("mapban"));
                player.setProctf(Boolean.parseBoolean(rs.getString("proctf")));
            }
        } catch (SQLException e) {
            log.warn("Exception: ", e);
            return null;
        }
        try {
            readSpree(player);
            sql = "SELECT start, end, reason, pardon, forgiven FROM banlist WHERE player_userid=? AND player_urtauth=?";
            try (PreparedStatement banstmt = c.prepareStatement(sql)) {
                banstmt.setString(1, player.getDiscordUser().getId());
                banstmt.setString(2, player.getUrtauth());
                try (ResultSet banSet = banstmt.executeQuery()) {
                    while (banSet.next()) {
                        BanReason reason = BanReason.fromStorage(banSet.getString("reason"));
                        if (reason == null) {
                            log.warn("Skipping ban with unknown reason '{}' for player {}", banSet.getString("reason"), player.getUrtauth());
                            continue;
                        }

                        PlayerBan ban = new PlayerBan();
                        ban.player = player;
                        ban.startTime = banSet.getLong("start");
                        ban.endTime = banSet.getLong("end");
                        ban.reason = reason;
                        ban.pardon = banSet.getString("pardon").matches("^[0-9]*$") ? discordService.getUserById(banSet.getString("pardon")) : null;
                        ban.forgiven = banSet.getBoolean("forgiven");
                        player.addBan(ban);
                    }
                }
            }
            player.setRank(readRankForPlayer(player));
            long statsRevision = Player.currentSeasonStatsRevision();
            player.setCurrentSeasonStats(readPlayerStats(player, logic.currentSeason), logic.currentSeason, statsRevision);
        } catch (SQLException e) {
            log.warn("Exception: ", e);
            return null;
        }
        return Player.publishLoaded(player, startedAt, onlyActive);
    }

    public void updatePlayerCountry(Player player, String country) {
        try {
            String sql = "UPDATE player SET country=? WHERE userid=?";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, country);
            pstmt.setString(2, player.getDiscordUser().getId());
            pstmt.executeUpdate();
            pstmt.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
    }

    // UPDATE SERVER
    public void updateServer(Server server) {
        try {
            String sql = "UPDATE server SET ip=?, port=?, rcon=?, password=?, active=?, region=? WHERE id=?";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, server.IP);
            pstmt.setInt(2, server.port);
            pstmt.setString(3, server.rconpassword);
            pstmt.setString(4, server.password);
            pstmt.setString(5, String.valueOf(server.active));
            pstmt.setString(6, server.region.toString());
            pstmt.setInt(7, server.id);
            pstmt.executeUpdate();
            pstmt.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
    }


    public void updateMap(GameMap map, Gametype gametype) {
        try {
            String sql = "SELECT * FROM map WHERE map=? AND gametype=?";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, map.name);
            pstmt.setString(2, gametype.getName());
            ResultSet rs = pstmt.executeQuery();
            if (!rs.next()) {
                createMap(map, gametype);
                return;
            }
            sql = "UPDATE map SET active=? WHERE map=? AND gametype=?";
            pstmt = c.prepareStatement(sql);
            pstmt.setString(1, String.valueOf(map.isActiveForGametype(gametype)));
            pstmt.setString(2, map.name);
            pstmt.setString(3, gametype.getName());
            pstmt.executeUpdate();
            pstmt.close();
            rs.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
    }

    public void updateChannel(DiscordChannel channel, PickupChannelType type) {
        try {
            String sql = "SELECT * FROM channels WHERE channel=?";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, channel.getId());
            ResultSet rs = pstmt.executeQuery();
            if (!rs.next()) {
                sql = "INSERT INTO channels (channel) VALUES (?)";
                pstmt = c.prepareStatement(sql);
                pstmt.setString(1, channel.getId());
                pstmt.executeUpdate();
            }
            sql = "UPDATE channels SET type=? WHERE channel=?";
            pstmt = c.prepareStatement(sql);
            pstmt.setString(1, type.name());
            pstmt.setString(2, channel.getId());
            pstmt.executeUpdate();
            pstmt.close();
            rs.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
    }

    public void updateRole(DiscordRole role, PickupRoleType type) {
        try {
            String sql = "SELECT * FROM roles WHERE role=?";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, role.getId());
            ResultSet rs = pstmt.executeQuery();
            if (!rs.next()) {
                sql = "INSERT INTO roles (role) VALUES (?)";
                pstmt = c.prepareStatement(sql);
                pstmt.setString(1, role.getId());
                pstmt.executeUpdate();
            }
            sql = "UPDATE roles SET type=? WHERE role=?";
            pstmt = c.prepareStatement(sql);
            pstmt.setString(1, type.name());
            pstmt.setString(2, role.getId());
            pstmt.executeUpdate();
            pstmt.close();
            rs.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
    }


    // SAVE MATCH

    public synchronized void saveMatch(Match match) {
        writeMatch("save match " + match.getID(), () -> {
                try (PreparedStatement updateMatch = matchWrites.prepareStatement(
                        "UPDATE match SET state=?, score_red=?, score_blue=? WHERE id=?");
                     PreparedStatement findPlayer = matchWrites.prepareStatement(
                             "SELECT pim.ID, st.score_1, st.score_2 FROM player_in_match pim "
                                     + "JOIN stats st ON st.pim=pim.ID WHERE pim.matchid=? AND pim.player_userid=? AND pim.player_urtauth=?");
                     PreparedStatement updateStats = matchWrites.prepareStatement("UPDATE stats SET ip=?, status=? WHERE pim=?");
                     PreparedStatement updateScore = matchWrites.prepareStatement(
                             "UPDATE score SET kills=?, deaths=?, assists=?, caps=?, returns=?, fckills=?, stopcaps=?, protflag=? WHERE ID=?");
                     PreparedStatement updatePlayer = matchWrites.prepareStatement(
                             "UPDATE player SET elo=?, elochange=? WHERE userid=? AND urtauth=?")) {
                    updateMatch.setString(1, match.getMatchState().name());
                    updateMatch.setInt(2, match.getScoreRed());
                    updateMatch.setInt(3, match.getScoreBlue());
                    updateMatch.setInt(4, match.getID());
                    if (updateMatch.executeUpdate() != 1) {
                        throw new SQLException("Match not found: " + match.getID());
                    }

                    for (Player player : match.getPlayerList()) {
                        findPlayer.setInt(1, match.getID());
                        findPlayer.setString(2, player.getDiscordUser().getId());
                        findPlayer.setString(3, player.getUrtauth());
                        int pim;
                        int[] scoreIds;
                        try (ResultSet rs = findPlayer.executeQuery()) {
                            if (!rs.next()) {
                                throw new SQLException("Player not found in match: " + match.getID());
                            }
                            pim = rs.getInt("ID");
                            scoreIds = new int[]{rs.getInt("score_1"), rs.getInt("score_2")};
                        }

                        MatchStats stats = match.getStats(player);
                        updateStats.setString(1, stats.getIP());
                        updateStats.setString(2, stats.getStatus().name());
                        updateStats.setInt(3, pim);
                        updateStats.executeUpdate();

                        for (int i = 0; i < 2; i++) {
                            updateScore.setInt(1, stats.score[i].score);
                            updateScore.setInt(2, stats.score[i].deaths);
                            updateScore.setInt(3, stats.score[i].assists);
                            updateScore.setInt(4, stats.score[i].caps);
                            updateScore.setInt(5, stats.score[i].returns);
                            updateScore.setInt(6, stats.score[i].fc_kills);
                            updateScore.setInt(7, stats.score[i].stop_caps);
                            updateScore.setInt(8, stats.score[i].protect_flag);
                            updateScore.setInt(9, scoreIds[i]);
                            updateScore.executeUpdate();
                        }

                        updatePlayer.setInt(1, player.getElo());
                        updatePlayer.setInt(2, player.getEloChange());
                        updatePlayer.setString(3, player.getDiscordUser().getId());
                        updatePlayer.setString(4, player.getUrtauth());
                        updatePlayer.executeUpdate();
                    }
                }
                return null;
        });
    }

    /** Escrow the stake in the same transaction as the open bet. Returns false if funds ran out. */
    public synchronized boolean placeBet(Bet bet, boolean allIn) {
        boolean placed = writeMatch("place bet for match " + bet.matchid, () -> {
            try (PreparedStatement state = matchWrites.prepareStatement("SELECT state FROM match WHERE ID=?");
                  PreparedStatement balance = matchWrites.prepareStatement("SELECT coins FROM player WHERE userid=? AND urtauth=?");
                  PreparedStatement existing = matchWrites.prepareStatement(
                          "SELECT ID, amount, typeof(amount) FROM bets WHERE matchid=? AND player_userid=? AND player_urtauth=? AND team=? AND open=1");
                 PreparedStatement debit = matchWrites.prepareStatement(
                         "UPDATE player SET coins=coins-? WHERE userid=? AND urtauth=? AND coins>=?");
                 PreparedStatement insert = matchWrites.prepareStatement(
                         "INSERT INTO bets (player_userid, player_urtauth, matchid, team, won, amount, odds, open) VALUES (?, ?, ?, ?, 'false', ?, ?, 1)");
                  PreparedStatement increase = matchWrites.prepareStatement("UPDATE bets SET amount=? WHERE ID=?")) {
                state.setInt(1, bet.matchid);
                try (ResultSet rs = state.executeQuery()) {
                    if (!rs.next() || !(rs.getString(1).equals("Live") || rs.getString(1).equals("AwaitingServer"))) return false;
                }
                String user = bet.player.getDiscordUser().getId();
                String auth = bet.player.getUrtauth();
                balance.setString(1, user);
                balance.setString(2, auth);
                long coins;
                try (ResultSet rs = balance.executeQuery()) {
                    if (!rs.next()) throw new SQLException("Bet player not found: " + auth);
                    coins = rs.getLong(1);
                }
                long amount = allIn ? coins : bet.amount;
                if (amount <= 0 || amount > coins || (!allIn && amount > 1_000_000)
                        || !Float.isFinite(bet.odds) || bet.odds <= 0
                        || amount * (double) bet.odds >= Long.MAX_VALUE) return false;
                int team = bet.color.equals("red") ? 0 : 1;
                existing.setInt(1, bet.matchid);
                existing.setString(2, user);
                existing.setString(3, auth);
                existing.setInt(4, team);
                int existingId = 0;
                long combinedAmount = amount;
                try (ResultSet rs = existing.executeQuery()) {
                    if (rs.next()) {
                        existingId = rs.getInt(1);
                        if (!"integer".equals(rs.getString(3))) {
                            throw new SQLException("Invalid stored bet amount: " + existingId);
                        }
                        try {
                            combinedAmount = Math.addExact(rs.getLong(2), amount);
                        } catch (ArithmeticException overflow) {
                            return false;
                        }
                        if (!allIn && combinedAmount > 1_000_000) return false;
                    }
                }
                debit.setLong(1, amount);
                debit.setString(2, user);
                debit.setString(3, auth);
                debit.setLong(4, amount);
                if (debit.executeUpdate() != 1) return false;
                if (existingId != 0) {
                    increase.setLong(1, combinedAmount);
                    increase.setInt(2, existingId);
                    increase.executeUpdate(); // preserve the original odds
                } else {
                    insert.setString(1, user);
                    insert.setString(2, auth);
                    insert.setInt(3, bet.matchid);
                    insert.setInt(4, team);
                    insert.setLong(5, amount);
                    insert.setFloat(6, bet.odds);
                    insert.executeUpdate();
                }
                bet.amount = amount;
                return true;
            }
        });
        return placed;
    }

    /** One ledger row gates every wallet, spree and bet-history effect. Safe after uncertain commit. */
    public synchronized void settleMatch(int matchId) {
        writeMatch("settle match " + matchId, () -> {
            try (PreparedStatement match = matchWrites.prepareStatement(
                    "SELECT m.state, m.score_red, m.score_blue, m.gametype, s.teamsize, s.settled "
                            + "FROM match m JOIN match_settlement s ON s.matchid=m.ID WHERE m.ID=?");
                 PreparedStatement players = matchWrites.prepareStatement(
                         "SELECT player_userid, player_urtauth, team FROM player_in_match WHERE matchid=?");
                 PreparedStatement bets = matchWrites.prepareStatement(
                         "SELECT ID, player_userid, player_urtauth, team, amount, odds FROM bets WHERE matchid=? AND open=1");
                 PreparedStatement balance = matchWrites.prepareStatement("SELECT coins FROM player WHERE userid=? AND urtauth=?");
                  PreparedStatement credit = matchWrites.prepareStatement("UPDATE player SET coins=? WHERE userid=? AND urtauth=?");
                  PreparedStatement spree = matchWrites.prepareStatement(
                          "SELECT spree FROM spree WHERE player_userid=? AND player_urtauth=? AND gametype=? ORDER BY ID DESC LIMIT 1");
                  PreparedStatement updateSpree = matchWrites.prepareStatement(
                          "UPDATE spree SET spree=?, personal_best=max(personal_best, ?), personal_worst=min(personal_worst, ?) "
                                  + "WHERE player_userid=? AND player_urtauth=? AND gametype=?");
                 PreparedStatement insertSpree = matchWrites.prepareStatement(
                         "INSERT INTO spree (player_userid, player_urtauth, gametype, spree, personal_best, personal_worst) VALUES (?, ?, ?, ?, ?, ?)");
                 PreparedStatement closeBet = matchWrites.prepareStatement("UPDATE bets SET open=0, won=? WHERE ID=?");
                 PreparedStatement deleteBet = matchWrites.prepareStatement("DELETE FROM bets WHERE ID=?");
                 PreparedStatement done = matchWrites.prepareStatement("UPDATE match_settlement SET settled=1 WHERE matchid=? AND settled=0")) {
                match.setInt(1, matchId);
                String state, gt;
                int red, blue, teamSize;
                try (ResultSet rs = match.executeQuery()) {
                    if (!rs.next()) throw new SQLException("No settlement record for match " + matchId);
                    if (rs.getInt(6) != 0) return null;
                    state = rs.getString(1);
                    red = rs.getInt(2);
                    blue = rs.getInt(3);
                    gt = rs.getString(4);
                    teamSize = rs.getInt(5);
                    if (rs.wasNull() || teamSize < 0) {
                        throw new SQLException("Match " + matchId + " has no reliable original team size; manual reconciliation required");
                    }
                }
                boolean completed = state.equals("Done") || state.equals("Mercy");
                if (!completed && !state.equals("Abort") && !state.equals("Abandon") && !state.equals("Surrender")) {
                    throw new SQLException("Match not terminal: " + matchId + " (" + state + ")");
                }
                boolean decisive = completed && red != blue && teamSize > 0;
                String winner = red > blue ? "red" : "blue";
                String spreeGt = gt.equals("PROMOD") ? "TS" : gt;
                Map<String, Long> deltas = new LinkedHashMap<>();
                players.setInt(1, matchId);
                try (ResultSet rs = players.executeQuery()) {
                    while (rs.next()) {
                        String user = rs.getString(1), auth = rs.getString(2), team = rs.getString(3);
                        if (decisive) {
                            deltas.merge(user + "\u0000" + auth, (long) (team.equals(winner) ? (state.equals("Mercy") ? 75 : 50) : 25), Math::addExact);
                            spree.setString(1, user);
                            spree.setString(2, auth);
                            spree.setString(3, spreeGt);
                            boolean exists = false;
                            int previous = 0;
                            try (ResultSet row = spree.executeQuery()) {
                                if (row.next()) { exists = true; previous = row.getInt(1); }
                            }
                            boolean won = team.equals(winner);
                            int next = won ? (previous > 0 ? previous + 1 : 1) : (previous < 0 ? previous - 1 : -1);
                            if (exists) {
                                updateSpree.setInt(1, next);
                                updateSpree.setInt(2, next);
                                updateSpree.setInt(3, next);
                                updateSpree.setString(4, user);
                                updateSpree.setString(5, auth);
                                updateSpree.setString(6, spreeGt);
                                updateSpree.addBatch();
                            } else {
                                insertSpree.setString(1, user);
                                insertSpree.setString(2, auth);
                                insertSpree.setString(3, spreeGt);
                                insertSpree.setInt(4, next);
                                insertSpree.setInt(5, Math.max(0, next));
                                insertSpree.setInt(6, Math.min(0, next));
                                insertSpree.addBatch();
                            }
                        }
                    }
                }
                updateSpree.executeBatch();
                insertSpree.executeBatch();
                bets.setInt(1, matchId);
                try (ResultSet rs = bets.executeQuery()) {
                    while (rs.next()) {
                        int id = rs.getInt(1);
                        String user = rs.getString(2), auth = rs.getString(3);
                        long amount = rs.getLong(5);
                        if (amount <= 0) throw new SQLException("Invalid open bet " + id);
                        if (decisive) {
                            boolean won = rs.getInt(4) == (winner.equals("red") ? 0 : 1);
                            if (won) deltas.merge(user + "\u0000" + auth, Math.round((double) amount * rs.getFloat(6)), Math::addExact);
                            closeBet.setString(1, String.valueOf(won));
                            closeBet.setInt(2, id);
                            closeBet.addBatch();
                        } else {
                            deltas.merge(user + "\u0000" + auth, amount, Math::addExact);
                            deleteBet.setInt(1, id);
                            deleteBet.addBatch();
                        }
                    }
                }
                closeBet.executeBatch();
                deleteBet.executeBatch();
                for (var entry : deltas.entrySet()) {
                    String[] key = entry.getKey().split("\u0000", -1);
                    balance.setString(1, key[0]); balance.setString(2, key[1]);
                    long current;
                    try (ResultSet rs = balance.executeQuery()) {
                        if (!rs.next()) throw new SQLException("Settlement player not found: " + entry.getKey());
                        current = rs.getLong(1);
                    }
                    credit.setLong(1, Math.addExact(current, entry.getValue()));
                    credit.setString(2, key[0]); credit.setString(3, key[1]);
                    credit.addBatch();
                }
                credit.executeBatch();
                done.setInt(1, matchId);
                if (done.executeUpdate() != 1) throw new SQLException("Settlement already completed: " + matchId);
                return null;
            }
        });
    }

    public synchronized void recoverSettlements() {
        List<Integer> ids = new ArrayList<>();
        try (PreparedStatement stmt = c.prepareStatement("SELECT s.matchid FROM match_settlement s JOIN match m ON m.ID=s.matchid "
                + "WHERE s.settled=0 AND m.state IN ('Done','Mercy','Abort','Abandon','Surrender')");
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) ids.add(rs.getInt(1));
        } catch (SQLException e) {
            throw new MatchPersistenceException("Unable to find pending settlements", e);
        }
        for (int id : ids) settleMatch(id);
    }

    public synchronized long walletBalance(Player player) {
        try (PreparedStatement stmt = matchWrites.prepareStatement("SELECT coins FROM player WHERE userid=? AND urtauth=?")) {
            stmt.setString(1, player.getDiscordUser().getId());
            stmt.setString(2, player.getUrtauth());
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) throw new SQLException("Wallet not found: " + player.getUrtauth());
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new MatchPersistenceException("Unable to read wallet", e);
        }
    }

    // need to check whether this is newly created or not
    public void updateGametype(Gametype gt) {
        try {
            String sql = "SELECT gametype FROM gametype WHERE gametype=?";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, gt.getName());
            ResultSet rs = pstmt.executeQuery();
            if (!rs.next()) {
                sql = "INSERT INTO gametype (gametype) VALUES (?)";
                pstmt = c.prepareStatement(sql);
                pstmt.setString(1, gt.getName());
                pstmt.executeUpdate();
            }
            sql = "UPDATE gametype SET teamsize=?, active=? WHERE gametype=?";
            pstmt = c.prepareStatement(sql);
            pstmt.setInt(1, gt.getTeamSize());
            pstmt.setString(2, String.valueOf(gt.getActive()));
            pstmt.setString(3, gt.getName());
            pstmt.executeUpdate();
            pstmt.close();
            rs.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
    }

    public boolean removePlayer(Player player) {
        try {
            String sql = "UPDATE player SET active=? WHERE userid=? AND urtauth=?";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, String.valueOf(false));
            pstmt.setString(2, player.getDiscordUser().getId());
            pstmt.setString(3, player.getUrtauth());
            int updatedRows = pstmt.executeUpdate();
            pstmt.close();
            return updatedRows > 0;
        } catch (SQLException e) {
            log.warn("Exception: ", e);
            return false;
        }
    }

    public void enforcePlayerAC(Player player) {
        try {
            String sql = "UPDATE player SET enforce_ac=? WHERE userid=? AND urtauth=?";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, String.valueOf(player.getEnforceAC()));
            pstmt.setString(2, player.getDiscordUser().getId());
            pstmt.setString(3, player.getUrtauth());
            pstmt.executeUpdate();
            pstmt.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
    }

    public void setProctfPlayer(Player player) {
        try {
            String sql = "UPDATE player SET proctf=? WHERE userid=? AND urtauth=?";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, String.valueOf(player.getProctf()));
            pstmt.setString(2, player.getDiscordUser().getId());
            pstmt.setString(3, player.getUrtauth());
            pstmt.executeUpdate();
            pstmt.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
    }


    public List<Player> getTopPlayers(int number) {
        List<Player> list = new ArrayList<Player>();
        try {
            String sql = "SELECT urtauth FROM player WHERE active=? ORDER BY elo DESC LIMIT ?";
            PreparedStatement pstmt = getPreparedStatement(sql);
            pstmt.setString(1, String.valueOf(true));
            pstmt.setInt(2, number);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                Player p = Player.get(rs.getString("urtauth"));
                list.add(p);
            }
            rs.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
        return list;
    }

    public record BanCount(String auth, int total, int active) { }

    public List<BanCount> getTopBans(int number) {
        List<BanCount> top = new ArrayList<>();
        String sql = "SELECT player_urtauth, COUNT(*) AS total, "
                + "SUM(CASE WHEN end > ? AND COALESCE(forgiven, 0) = 0 THEN 1 ELSE 0 END) AS active "
                + "FROM banlist WHERE player_urtauth IS NOT NULL "
                + "GROUP BY player_urtauth ORDER BY total DESC, player_urtauth COLLATE NOCASE LIMIT ?";
        try (PreparedStatement stmt = c.prepareStatement(sql)) {
            stmt.setLong(1, System.currentTimeMillis());
            stmt.setInt(2, number);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    top.add(new BanCount(rs.getString("player_urtauth"), rs.getInt("total"), rs.getInt("active")));
                }
            }
        } catch (SQLException e) {
            log.warn("Unable to load ban leaderboard", e);
        }
        return top;
    }

    public ArrayList<CountryRank> getTopCountries(int number) {
        ArrayList<CountryRank> list = new ArrayList<CountryRank>();
        try {
            String sql = "SELECT AVG(elo) as Average_Elo, country FROM player WHERE active=? GROUP BY country ORDER BY Average_Elo DESC LIMIT ?";
            PreparedStatement pstmt = getPreparedStatement(sql);
            pstmt.setString(1, String.valueOf(true));
            pstmt.setInt(2, number);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                if (!rs.getString("country").equalsIgnoreCase("NOT_DEFINED")) {
                    list.add(new CountryRank(rs.getString("country"), rs.getFloat("Average_Elo")));
                }
            }
            rs.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
        return list;
    }

    public int getRankForPlayer(Player player) {
        try {
            return readRankForPlayer(player);
        } catch (SQLException e) {
            log.warn("Exception: ", e);
            return -1;
        }
    }

    private int readRankForPlayer(Player player) throws SQLException {
        String sql = "SELECT (SELECT COUNT(*) FROM player b WHERE a.elo < b.elo AND active=?) AS rank FROM player a WHERE userid=? AND urtauth=?";
        try (PreparedStatement pstmt = c.prepareStatement(sql)) {
            pstmt.setString(1, String.valueOf(true));
            pstmt.setString(2, player.getDiscordUser().getId());
            pstmt.setString(3, player.getUrtauth());
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next() ? rs.getInt("rank") + 1 : -1;
            }
        }
    }

    public WinDrawLoss getWDLForPlayer(Player player, Gametype gt, Season season) {
        try {
            return readWDLForPlayer(player, gt, season);
        } catch (SQLException e) {
            log.warn("Exception: ", e);
            return new WinDrawLoss();
        }
    }

    private WinDrawLoss readWDLForPlayer(Player player, Gametype gt, Season season) throws SQLException {
        WinDrawLoss wdl = new WinDrawLoss();
        if (gt == null) {
            return wdl;
        }
        String gametypeCondition;
        if (gt.getName().equals("TS")) {
            gametypeCondition = "AND (m.gametype='TS' OR m.gametype='PROMOD')";
        } else {
            gametypeCondition = "AND m.gametype=?";
        }

        String sql = "SELECT SUM(CASE WHEN stat.myscore > stat.oppscore THEN 1 ELSE 0 END) AS win, "
                + "SUM(CASE WHEN stat.myscore = stat.oppscore THEN 1 END) AS draw, "
                + "SUM(CASE WHEN stat.myscore < stat.oppscore THEN 1 END) AS loss "
                + "FROM ("
                + "SELECT pim.player_urtauth AS urtauth, "
                + "(CASE WHEN pim.team = 'red' THEN m.score_red ELSE m.score_blue END) AS myscore, "
                + "(CASE WHEN pim.team = 'blue' THEN m.score_red ELSE m.score_blue END) AS oppscore "
                + "FROM 'player_in_match' AS pim "
                + "JOIN 'match' AS m ON m.id = pim.matchid "
                + "JOIN 'player' AS p ON pim.player_urtauth=p.urtauth AND pim.player_userid=p.userid "
                + "WHERE (m.state = 'Done' OR m.state = 'Surrender' OR m.state = 'Mercy') " + gametypeCondition + " AND m.starttime > ? AND m.starttime < ?"
                + "AND p.urtauth=? AND p.userid=?) AS stat ";
        try (PreparedStatement pstmt = c.prepareStatement(sql)) {
            int paramIndex = 1;
            if (!gt.getName().equals("TS")) {
                pstmt.setString(paramIndex++, gt.getName());
            }
            pstmt.setLong(paramIndex++, season.startdate);
            pstmt.setLong(paramIndex++, season.enddate);
            pstmt.setString(paramIndex++, player.getUrtauth());
            pstmt.setString(paramIndex, player.getDiscordUser().getId());
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    wdl.win = rs.getInt("win");
                    wdl.draw = rs.getInt("draw");
                    wdl.loss = rs.getInt("loss");
                }
            }
        }
        return wdl;
    }

    public int getWDLRankForPlayer(Player player, Gametype gt, Season season) {
        try {
            return readWDLRankForPlayer(player, gt, season);
        } catch (SQLException e) {
            log.warn("Exception: ", e);
            return -1;
        }
    }

    private int readWDLRankForPlayer(Player player, Gametype gt, Season season) throws SQLException {
        if (gt == null) {
            return -1;
        }
        int limit = 20;
        if (season.number == 0) {
            limit = 100;
        }
        if (gt.getName().equals("CTF")) {
            limit = 10;
        }

        String gametypeCondition;
        if (gt.getName().equals("TS")) {
            gametypeCondition = "AND (m.gametype='TS' OR m.gametype='PROMOD')";
        } else {
            gametypeCondition = "AND m.gametype=?";
        }

        int minimumGames = limit;
        return cachedRank(player, gt, season, true, () -> {
            // Drive from season matches, then rank all eligible players once per revision.
            String sql = "WITH tablewdl (urtauth, matchcount, winrate) AS (SELECT urtauth, COUNT(urtauth) as matchcount, (CAST(SUM(CASE WHEN stat.myscore > stat.oppscore THEN 1 ELSE 0 END) AS FLOAT)+ CAST(SUM(CASE WHEN stat.myscore = stat.oppscore THEN 1 ELSE 0 END) AS FLOAT)/2)/(CAST(SUM(CASE WHEN stat.myscore > stat.oppscore THEN 1 ELSE 0 END) AS FLOAT)+ CAST(SUM(CASE WHEN stat.myscore = stat.oppscore THEN 1 ELSE 0 END) AS FLOAT) + CAST(SUM(CASE WHEN stat.myscore < stat.oppscore THEN 1 ELSE 0 END) AS FLOAT)) as winrate FROM (SELECT pim.player_urtauth AS urtauth, (CASE WHEN pim.team = 'red' THEN m.score_red ELSE m.score_blue END) AS myscore, (CASE WHEN pim.team = 'blue' THEN m.score_red ELSE m.score_blue END) AS oppscore FROM 'match' AS m CROSS JOIN 'player_in_match' AS pim CROSS JOIN 'player' AS p WHERE pim.matchid=m.id AND pim.player_urtauth=p.urtauth AND pim.player_userid=p.userid AND p.active='true' AND (m.state = 'Done' OR m.state = 'Surrender' OR m.state = 'Mercy') AND m.starttime > ? AND m.starttime < ? " + gametypeCondition + ") AS stat GROUP BY urtauth HAVING COUNT(urtauth) > ?) SELECT urtauth AS auth, CASE WHEN winrate IS NULL THEN 1 ELSE RANK() OVER (ORDER BY winrate DESC) END AS rowIndex FROM tablewdl";
            try (PreparedStatement pstmt = c.prepareStatement(sql)) {
                int paramIndex = 1;
                pstmt.setLong(paramIndex++, season.startdate);
                pstmt.setLong(paramIndex++, season.enddate);
                if (!gt.getName().equals("TS")) {
                    pstmt.setString(paramIndex++, gt.getName());
                }
                pstmt.setInt(paramIndex, minimumGames);
                try (ResultSet rs = pstmt.executeQuery()) {
                    return readRanks(rs);
                }
            }
        });
    }

    public int getKDRRankForPlayer(Player player, Gametype gt, Season season) {
        try {
            return readKDRRankForPlayer(player, gt, season);
        } catch (SQLException e) {
            log.warn("Exception: ", e);
            return -1;
        }
    }

    private int readKDRRankForPlayer(Player player, Gametype gt, Season season) throws SQLException {
        if (gt == null) {
            return -1;
        }
        int limit = 20;
        if (season.number == 0) {
            limit = 100;
        }

        String rating_query = "(CAST(SUM(kills) AS FLOAT) + CAST(SUM(assists) AS FLOAT)/2) / CAST(SUM(deaths) AS FLOAT)";
        if (gt.getName().equals("CTF")) {
            limit = 10;
            rating_query = "CAST (SUM(score.kills) AS FLOAT) / (COUNT(player_in_match.player_urtauth)/2 ) / 50";
        }

        String gametypeCondition;
        if (gt.getName().equals("TS")) {
            gametypeCondition = "AND (match.gametype='TS' OR match.gametype='PROMOD')";
        } else {
            gametypeCondition = "AND match.gametype=?";
        }

        int minimumGames = limit;
        String rating = rating_query;
        return cachedRank(player, gt, season, false, () -> {
            String sql = "WITH tablekdr (auth, matchcount, kdr) AS (SELECT player.urtauth AS auth, COUNT(player_in_match.player_urtauth)/2 as matchcount, " + rating + " AS kdr FROM (score INNER JOIN stats ON stats.score_1 = score.ID OR stats.score_2 = score.ID INNER JOIN player_in_match ON player_in_match.ID = stats.pim INNER JOIN player ON player_in_match.player_userid = player.userid INNER JOIN match ON player_in_match.matchid = match.id) WHERE player.active = 'true' AND (match.state = 'Done' OR match.state = 'Surrender' OR match.state = 'Mercy') " + gametypeCondition + " AND match.starttime > ? AND match.starttime < ? GROUP BY player_in_match.player_urtauth HAVING matchcount > ?) SELECT auth, CASE WHEN kdr IS NULL THEN 1 ELSE RANK() OVER (ORDER BY kdr DESC) END AS rowIndex FROM tablekdr";
            try (PreparedStatement pstmt = c.prepareStatement(sql)) {
                int paramIndex = 1;
                if (!gt.getName().equals("TS")) {
                    pstmt.setString(paramIndex++, gt.getName());
                }
                pstmt.setLong(paramIndex++, season.startdate);
                pstmt.setLong(paramIndex++, season.enddate);
                pstmt.setInt(paramIndex, minimumGames);
                try (ResultSet rs = pstmt.executeQuery()) {
                    return readRanks(rs);
                }
            }
        });
    }

    public Map<Player, String> getTopWDL(int number, Gametype gt, Season season) {
        Map<Player, String> topwdl = new LinkedHashMap<Player, String>();
        try {
            int limit = 20;
            if (season.number == 0) {
                limit = 100;
            }
            if (gt.getName().equals("CTF")) {
                limit = 10;
            }
            String gametypeCondition;
            if (gt.getName().equals("TS")) {
                gametypeCondition = "AND (m.gametype='TS' OR m.gametype='PROMOD')";
            } else {
                gametypeCondition = "AND m.gametype=?";
            }

            // Filter to the season's matches before walking player history, regardless of
            // whether the database has fresh planner statistics.
            String sql = "SELECT urtauth, COUNT(urtauth) as matchcount, SUM(CASE WHEN stat.myscore > stat.oppscore THEN 1 ELSE 0 END) as win, SUM(CASE WHEN stat.myscore = stat.oppscore THEN 1 ELSE 0 END) as draw, SUM(CASE WHEN stat.myscore < stat.oppscore THEN 1 ELSE 0 END) loss , (CAST(SUM(CASE WHEN stat.myscore > stat.oppscore THEN 1 ELSE 0 END) AS FLOAT)+ CAST(SUM(CASE WHEN stat.myscore = stat.oppscore THEN 1 ELSE 0 END) AS FLOAT)/2)/(CAST(SUM(CASE WHEN stat.myscore > stat.oppscore THEN 1 ELSE 0 END) AS FLOAT)+ CAST(SUM(CASE WHEN stat.myscore = stat.oppscore THEN 1 ELSE 0 END) AS FLOAT) + CAST(SUM(CASE WHEN stat.myscore < stat.oppscore THEN 1 ELSE 0 END) AS FLOAT)) as winrate FROM (SELECT pim.player_urtauth AS urtauth, (CASE WHEN pim.team = 'red' THEN m.score_red ELSE m.score_blue END) AS myscore, (CASE WHEN pim.team = 'blue' THEN m.score_red ELSE m.score_blue END) AS oppscore FROM 'match' AS m CROSS JOIN 'player_in_match' AS pim CROSS JOIN 'player' AS p WHERE pim.matchid=m.id AND pim.player_urtauth=p.urtauth AND pim.player_userid=p.userid AND p.active='true' AND (m.state = 'Done' OR m.state = 'Surrender' OR m.state = 'Mercy') AND m.starttime > ? AND m.starttime < ? " + gametypeCondition + ") AS stat GROUP BY urtauth HAVING COUNT(urtauth) > ? ORDER BY winrate DESC LIMIT ?";
            PreparedStatement pstmt = getPreparedStatement(sql);

            int paramIndex = 1;
            pstmt.setLong(paramIndex++, season.startdate);
            pstmt.setLong(paramIndex++, season.enddate);
            if (!gt.getName().equals("TS")) {
                pstmt.setString(paramIndex++, gt.getName());
            }
            pstmt.setLong(paramIndex++, limit);
            pstmt.setInt(paramIndex, number);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                Player p = Player.get(rs.getString("urtauth"));
                String entry = Long.toString(Math.round(rs.getFloat("winrate") * 100d)) + "%  (*" + Integer.toString(rs.getInt("win") + rs.getInt("loss")) + "*)";
                topwdl.put(p, entry);
            }
            rs.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
        return topwdl;
    }

    public Map<Player, Float> getTopKDR(int number, Gametype gt, Season season) {
        Map<Player, Float> topkdr = new LinkedHashMap<Player, Float>();
        try {
            int limit = 20;
            if (season.number == 0) {
                limit = 100;
            }

            String rating_query = "(CAST(SUM(kills) AS FLOAT) + CAST(SUM(assists) AS FLOAT)/2) / CAST(SUM(deaths) AS FLOAT)";
            if (gt.getName().equals("CTF")) {
                limit = 10;
                rating_query = "CAST (SUM(score.kills) AS FLOAT) / (COUNT(player_in_match.player_urtauth)/2 ) / 50";
            } else if (gt.getName().equals("SKEET") || gt.getName().equals("AIM")) {
                limit = 0;
                rating_query = "MAX(kills)";
            }
            String gametypeCondition;
            if (gt.getName().equals("TS")) {
                gametypeCondition = "AND (match.gametype='TS' OR match.gametype='PROMOD')";
            } else {
                gametypeCondition = "AND match.gametype=?";
            }

            String sql = "SELECT player.urtauth AS auth, COUNT(player_in_match.player_urtauth)/2 as matchcount, " + rating_query + " AS kdr FROM score INNER JOIN stats ON stats.score_1 = score.ID OR stats.score_2 = score.ID INNER JOIN player_in_match ON player_in_match.ID = stats.pim  INNER JOIN player ON player_in_match.player_userid = player.userid INNER JOIN match ON match.id = player_in_match.matchid WHERE player.active = \"true\" AND (match.state = 'Done' OR match.state = 'Surrender' OR match.state = 'Mercy') " + gametypeCondition + " AND match.starttime > ? AND match.starttime < ? GROUP BY player_in_match.player_urtauth HAVING matchcount > ? ORDER BY kdr DESC LIMIT ?";
            PreparedStatement pstmt = getPreparedStatement(sql);

            int paramIndex = 1;
            if (!gt.getName().equals("TS")) {
                pstmt.setString(paramIndex++, gt.getName());
            }
            pstmt.setLong(paramIndex++, season.startdate);
            pstmt.setLong(paramIndex++, season.enddate);
            pstmt.setLong(paramIndex++, limit);
            pstmt.setInt(paramIndex, number);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                Player p = Player.get(rs.getString("auth"));
                log.trace(p.getUrtauth());
                topkdr.put(p, rs.getFloat("kdr"));
            }
            rs.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
        return topkdr;
    }

    public Map<Player, Integer> getTopMatchPlayed(int number, Season season) {
        Map<Player, Integer> topmatchplayed = new LinkedHashMap<Player, Integer>();
        try {
            String sql = "SELECT pim.player_urtauth, COUNT(pim.player_urtauth) as matchplayed from player_in_match pim JOIN match m ON pim.matchid = m.ID WHERE m.state IN ('Done', 'Mercy', 'Surrender') AND m.gametype IN ('TS', 'DIV1', 'PROMOD', 'CTF', 'PROCTF') AND m.starttime > ? AND m.starttime < ? GROUP BY pim.player_urtauth ORDER BY matchplayed DESC LIMIT ?";
            PreparedStatement pstmt = getPreparedStatement(sql);
            pstmt.setLong(1, season.startdate);
            pstmt.setLong(2, season.enddate);
            pstmt.setInt(3, number);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                Player p = Player.get(rs.getString("player_urtauth"));
                topmatchplayed.put(p, rs.getInt("matchplayed"));
            }
            rs.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
        return topmatchplayed;
    }

    public int getAvgElo() {
        int elo = -1;
        try {
            String sql = "SELECT AVG(elo) AS avg_elo FROM player WHERE active=?";
            PreparedStatement pstmt = getPreparedStatement(sql);
            pstmt.setString(1, String.valueOf(true));
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                elo = rs.getInt("avg_elo");
            }
            rs.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
        return elo;
    }


    public void resetStats() {
        try {
            Statement stmt = c.createStatement();
            String sql = "DELETE FROM match";
            stmt.executeUpdate(sql);
            sql = "DELETE FROM player_in_match";
            stmt.executeUpdate(sql);
            sql = "DELETE FROM score";
            stmt.executeUpdate(sql);
            sql = "DELETE FROM stats";
            stmt.executeUpdate(sql);
            sql = "DELETE FROM SQLITE_SEQUENCE WHERE NAME='match' OR NAME='player_in_match' OR NAME='score' OR NAME='stats'";
            stmt.executeUpdate(sql);
            sql = "UPDATE player SET elo=1000, elochange=0";
            stmt.executeUpdate(sql);
            sql = "DELETE FROM player WHERE active='false'";
            stmt.executeUpdate(sql);
            stmt.close();
            Player.invalidateSeasonStats();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
    }

    public PlayerStats getPlayerStats(Player player, Season season) {
        PlayerStats stats = new PlayerStats();
        stats.kdrRank = getKDRRankForPlayer(player, logic.getGametypeByString("TS"), season);
        stats.ctfRank = getKDRRankForPlayer(player, logic.getGametypeByString("CTF"), season);
        stats.wdlRank = getWDLRankForPlayer(player, logic.getGametypeByString("TS"), season);
        stats.ctfWdlRank = getWDLRankForPlayer(player, logic.getGametypeByString("CTF"), season);
        stats.ts_wdl = getWDLForPlayer(player, logic.getGametypeByString("TS"), season);
        stats.ctf_wdl = getWDLForPlayer(player, logic.getGametypeByString("CTF"), season);
        try {
            readPlayerStatsValues(player, season, stats);
            player.setKdr(stats.kdr);
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
        return stats;
    }

    /**
     * Returns a complete, cacheable snapshot, or null if any SQL read fails.
     * Does not mutate the player; callers retain their previous snapshot and retry.
     * Historical display can still use the best-effort getPlayerStats method.
     */
    public PlayerStats tryGetPlayerStats(Player player, Season season) {
        try {
            return readPlayerStats(player, season);
        } catch (SQLException e) {
            log.warn("Unable to refresh season stats for {}", player.getUrtauth(), e);
            return null;
        }
    }

    // Hydration and cache refresh both require every stats query to succeed.
    private PlayerStats readPlayerStats(Player player, Season season) throws SQLException {
        PlayerStats stats = new PlayerStats();

        stats.kdrRank = readKDRRankForPlayer(player, logic.getGametypeByString("TS"), season);
        stats.ctfRank = readKDRRankForPlayer(player, logic.getGametypeByString("CTF"), season);

        stats.wdlRank = readWDLRankForPlayer(player, logic.getGametypeByString("TS"), season);
        stats.ctfWdlRank = readWDLRankForPlayer(player, logic.getGametypeByString("CTF"), season);

        stats.ts_wdl = readWDLForPlayer(player, logic.getGametypeByString("TS"), season);
        stats.ctf_wdl = readWDLForPlayer(player, logic.getGametypeByString("CTF"), season);

        readPlayerStatsValues(player, season, stats);
        return stats;
    }

    private void readPlayerStatsValues(Player player, Season season, PlayerStats stats) throws SQLException {
        String sql = "SELECT SUM(kills) as sumkills, SUM(deaths) as sumdeaths, SUM(assists) as sumassists FROM score INNER JOIN stats ON stats.score_1 = score.ID OR stats.score_2 = score.ID INNER JOIN player_in_match ON player_in_match.ID = stats.pim INNER JOIN match ON match.id = player_in_match.matchid WHERE (match.gametype=\"TS\" OR match.gametype=\"PROMOD\") AND (match.state = 'Done' OR match.state = 'Surrender' OR match.state = 'Mercy') AND player_userid=? AND player_urtauth=? AND match.starttime > ? AND match.starttime < ?;";
        try (PreparedStatement pstmt = c.prepareStatement(sql)) {
            pstmt.setString(1, player.getDiscordUser().getId());
            pstmt.setString(2, player.getUrtauth());
            pstmt.setLong(3, season.startdate);
            pstmt.setLong(4, season.enddate);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    float kdr = ((float) rs.getInt("sumkills") + (float) rs.getInt("sumassists") / 2) / (float) rs.getInt("sumdeaths");
                    stats.kdr = kdr;
                    stats.kills = rs.getInt("sumkills");
                    stats.assists = rs.getInt("sumassists");
                    stats.deaths = rs.getInt("sumdeaths");
                }
            }
        }

        // CTF
        sql = "SELECT COUNT(player_in_match.player_urtauth)/2 as matchcount, CAST (SUM(score.kills) AS FLOAT) / (COUNT(player_in_match.player_urtauth)/2 ) / 50   as ctfrating, SUM(caps) as sumcaps, SUM(returns) as sumreturns, SUM(fckills) as sumfckills, SUM(stopcaps) as sumstopcaps, SUM(protflag) as sumprotflag, player_in_match.player_urtauth as auth, match.id as matchid FROM score INNER JOIN stats ON (score.id = stats.score_1 OR score.id = stats.score_2) INNER JOIN player_in_match ON player_in_match.id = stats.pim INNER JOIN match ON player_in_match.matchid = match.id WHERE match.gametype=\"CTF\" AND (match.state = 'Done' OR match.state = 'Surrender' OR match.state = 'Mercy') AND auth=?  AND match.starttime > ? AND match.starttime < ?;";
        try (PreparedStatement pstmt = c.prepareStatement(sql)) {
            pstmt.setString(1, player.getUrtauth());
            pstmt.setLong(2, season.startdate);
            pstmt.setLong(3, season.enddate);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    stats.ctf_rating = rs.getFloat("ctfrating");
                    stats.caps = rs.getInt("sumcaps");
                    stats.returns = rs.getInt("sumreturns");
                    stats.fckills = rs.getInt("sumfckills");
                    stats.stopcaps = rs.getInt("sumstopcaps");
                    stats.protflag = rs.getInt("sumprotflag");
                }
            }
        }
    }

    public void resetElo() {
        try {
            // TODO: maybe move this somewhere
            String sql = "UPDATE player SET elo = 500 WHERE elo < 1200;";
            PreparedStatement pstmt = getPreparedStatement(sql);
            pstmt.executeUpdate();

            sql = "UPDATE player SET elo = 750 WHERE elo > 1200 AND elo < 1400;";
            pstmt = getPreparedStatement(sql);
            pstmt.executeUpdate();

            sql = "UPDATE player SET elo = 1000 WHERE elo > 1400;";
            pstmt = getPreparedStatement(sql);
            pstmt.executeUpdate();

        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
    }

    public Season getCurrentSeason() {
        String sql = "SELECT number, startdate, enddate FROM season ORDER BY number DESC LIMIT 1;";
        try (PreparedStatement pstmt = c.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            if (rs.next()) {
                int number = rs.getInt("number");
                long startdate = rs.getLong("startdate");
                long enddate = rs.getLong("enddate");
                return new Season(number, startdate, enddate);
            }
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
        return null;
    }

    public Season getSeason(int number) {
        String sql = "SELECT number, startdate, enddate FROM season WHERE number = ?;";
        try (PreparedStatement pstmt = c.prepareStatement(sql)) {
            pstmt.setInt(1, number);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    long startdate = rs.getLong("startdate");
                    long enddate = rs.getLong("enddate");
                    return new Season(number, startdate, enddate);
                }
            }
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
        return null;
    }

    public synchronized long updatePlayerCoins(Player player, long delta) {
        return writeMatch("update wallet", () -> {
            try (PreparedStatement select = matchWrites.prepareStatement("SELECT coins FROM player WHERE userid=? AND urtauth=?");
                 PreparedStatement update = matchWrites.prepareStatement("UPDATE player SET coins=? WHERE userid=? AND urtauth=?")) {
                String user = player.getDiscordUser().getId(), auth = player.getUrtauth();
                select.setString(1, user); select.setString(2, auth);
                long balance;
                try (ResultSet rs = select.executeQuery()) {
                    if (!rs.next()) throw new SQLException("Wallet not found: " + auth);
                    balance = Math.addExact(rs.getLong(1), delta);
                }
                if (balance < 0) throw new SQLException("Insufficient wallet balance: " + auth);
                update.setLong(1, balance); update.setString(2, user); update.setString(3, auth);
                if (update.executeUpdate() != 1) throw new SQLException("Wallet not found: " + auth);
                return balance;
            }
        });
    }

    public synchronized boolean transferCoins(Player sender, Player recipient, long amount) {
        if (amount <= 0) return false;
        try {
            ensureMatchWriter();
            if (!matchWrites.getAutoCommit()) throw new SQLException("Wallet writer already in a transaction");
            String from = sender.getDiscordUser().getId(), to = recipient.getDiscordUser().getId();
            String fromAuth = sender.getUrtauth(), toAuth = recipient.getUrtauth();
            if (from.equals(to) && fromAuth.equals(toAuth)) {
                try (PreparedStatement balance = matchWrites.prepareStatement("SELECT coins FROM player WHERE userid=? AND urtauth=?")) {
                    balance.setString(1, from);
                    balance.setString(2, fromAuth);
                    try (ResultSet rs = balance.executeQuery()) {
                        return rs.next() && rs.getLong(1) >= amount;
                    }
                }
            }

            // One atomic statement on the isolated writer cannot inherit an open
            // command-side read snapshot or join an unrelated command transaction.
            // Materialize eligibility once so updating the sender cannot change the
            // recipient row's eligibility during the same UPDATE.
            String sql = "WITH transfer AS MATERIALIZED (SELECT s.rowid AS sender_id, r.rowid AS recipient_id "
                    + "FROM player s JOIN player r ON r.userid=? AND r.urtauth=? "
                    + "WHERE s.userid=? AND s.urtauth=? AND s.coins>=? AND r.coins<=?) "
                    + "UPDATE player SET coins=CASE WHEN rowid=(SELECT sender_id FROM transfer) "
                    + "THEN coins-? ELSE coins+? END "
                    + "WHERE rowid IN (SELECT sender_id FROM transfer UNION ALL SELECT recipient_id FROM transfer)";
            try (PreparedStatement stmt = matchWrites.prepareStatement(sql)) {
                stmt.setString(1, to);
                stmt.setString(2, toAuth);
                stmt.setString(3, from);
                stmt.setString(4, fromAuth);
                stmt.setLong(5, amount);
                stmt.setLong(6, Long.MAX_VALUE - amount);
                stmt.setLong(7, amount);
                stmt.setLong(8, amount);
                return stmt.executeUpdate() == 2;
            }
        } catch (SQLException e) {
            throw new MatchPersistenceException("Unable to transfer coins", e);
        }
    }

    public enum Perk { ELO_BOOST, MAP_VOTES, MAP_BAN }

    public enum PurchaseStatus { PURCHASED, INSUFFICIENT_FUNDS, ALREADY_OWNED }

    public record PerkPurchase(PurchaseStatus status, long coins, long eloBoost, int mapVotes, int mapBans) { }

    /** Grant the perk and debit its price together, using persisted funds and ownership. */
    public synchronized PerkPurchase purchasePerk(Player player, Perk perk, int quantity) {
        if (quantity < 1 || quantity > (perk == Perk.MAP_VOTES ? 5 : 1)) {
            throw new IllegalArgumentException("Invalid perk quantity: " + quantity);
        }
        long price = switch (perk) {
            case ELO_BOOST -> 1000;
            case MAP_VOTES -> 1000L << (quantity - 1);
            case MAP_BAN -> 10000;
        };
        return writeMatch("purchase " + perk, () -> {
            String user = player.getDiscordUser().getId(), auth = player.getUrtauth();
            try (PreparedStatement select = matchWrites.prepareStatement(
                    "SELECT coins, eloboost, mapvote, mapban FROM player WHERE userid=? AND urtauth=?");
                 PreparedStatement update = matchWrites.prepareStatement(
                         "UPDATE player SET coins=?, eloboost=?, mapvote=?, mapban=? WHERE userid=? AND urtauth=?")) {
                select.setString(1, user);
                select.setString(2, auth);
                long coins, boost;
                int votes, bans;
                try (ResultSet rs = select.executeQuery()) {
                    if (!rs.next()) throw new SQLException("Wallet not found: " + auth);
                    coins = rs.getLong(1);
                    boost = rs.getLong(2);
                    votes = rs.getInt(3);
                    bans = rs.getInt(4);
                }
                long now = System.currentTimeMillis();
                if ((perk == Perk.ELO_BOOST && boost >= now) || (perk == Perk.MAP_VOTES && votes > 0)) {
                    return new PerkPurchase(PurchaseStatus.ALREADY_OWNED, coins, boost, votes, bans);
                }
                if (coins < price) {
                    return new PerkPurchase(PurchaseStatus.INSUFFICIENT_FUNDS, coins, boost, votes, bans);
                }
                coins -= price;
                switch (perk) {
                    case ELO_BOOST -> boost = now + 7_200_000;
                    case MAP_VOTES -> votes = quantity;
                    case MAP_BAN -> bans = Math.addExact(bans, 1);
                }
                update.setLong(1, coins);
                update.setLong(2, boost);
                update.setInt(3, votes);
                update.setInt(4, bans);
                update.setString(5, user);
                update.setString(6, auth);
                if (update.executeUpdate() != 1) throw new SQLException("Wallet not found: " + auth);
                return new PerkPurchase(PurchaseStatus.PURCHASED, coins, boost, votes, bans);
            }
        });
    }

    public void updatePlayerBoost(Player player) {
        try {
            String sql = "UPDATE player SET eloboost=?, mapvote=?, mapban=? WHERE userid=? AND urtauth=?";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setLong(1, player.getEloBoost());
            pstmt.setInt(2, player.getAdditionalMapVotes());
            pstmt.setInt(3, player.getMapBans());
            pstmt.setString(4, player.getDiscordUser().getId());
            pstmt.setString(5, player.getUrtauth());
            pstmt.executeUpdate();
            pstmt.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
    }

    public Map<Player, Long> getTopRich(int number) {
        Map<Player, Long> toprich = new LinkedHashMap<Player, Long>();
        try {
            String sql = "SELECT urtauth, coins FROM  player INNER JOIN bets ON (player.urtauth = bets.player_urtauth ) GROUP BY urtauth ORDER BY coins DESC LIMIT ?";
            PreparedStatement pstmt = getPreparedStatement(sql);
            pstmt.setInt(1, number);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                Player p = Player.get(rs.getString("urtauth"));
                log.trace(p.getUrtauth());
                toprich.put(p, rs.getLong("coins"));
            }
            rs.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
        return toprich;
    }

    public void updateMapBan(GameMap map) {
        try {
            String sql = "UPDATE map set banned_until = ? WHERE map = ?";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setLong(1, map.bannedUntil);
            pstmt.setString(2, map.name);
            pstmt.executeUpdate();
            pstmt.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }

    }

    public ArrayList<Bet> getBetHistory(Player p) {
        ArrayList<Bet> betList = new ArrayList<Bet>();
        try {
            String sql = "SELECT * from bets WHERE bets.player_urtauth = ? AND open=0 ORDER BY bets.ID DESC LIMIT 10;";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, p.getUrtauth());
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                int matchid = rs.getInt("matchid");
                String color = rs.getInt("team") == 0 ? "red" : "blue";
                long amount = rs.getLong("amount");
                float odds = rs.getFloat("odds");
                Bet bet = new Bet(matchid, p, color, amount, odds);
                bet.won = Boolean.parseBoolean(rs.getString("won"));
                betList.add(bet);
            }
            rs.close();
            pstmt.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
        return betList;
    }

    public void createSpree(Player player, Gametype gametype, int spree) {
        try {
            String sql = "INSERT INTO spree (player_userid, player_urtauth, gametype, spree, personal_best, personal_worst) VALUES (?, ?, ?, ?, ?, ?)";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, player.getDiscordUser().getId());
            pstmt.setString(2, player.getUrtauth());
            pstmt.setString(3, gametype.getName());
            pstmt.setInt(4, spree);
            pstmt.setInt(5, spree > 0 ? spree : 0);
            pstmt.setInt(6, spree < 0 ? spree : 0);
            pstmt.executeUpdate();
            pstmt.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
    }

    public void updateSpree(Player player, Gametype gametype, int spree) {
        try {
            String sql = "UPDATE spree SET spree = ?, personal_best = CASE WHEN ? > personal_best THEN ? ELSE personal_best END, personal_worst = CASE WHEN ? < personal_worst THEN ? ELSE personal_worst END WHERE player_urtauth = ? AND gametype = ?";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setInt(1, spree);
            pstmt.setInt(2, spree);
            pstmt.setInt(3, spree);
            pstmt.setInt(4, spree);
            pstmt.setInt(5, spree);
            pstmt.setString(6, player.getUrtauth());
            pstmt.setString(7, gametype.getName());
            pstmt.executeUpdate();
            pstmt.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
    }

    public void loadSpree(Player player) {
        try {
            readSpree(player);
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
    }

    private void readSpree(Player player) throws SQLException {
        String sql = "SELECT * FROM spree WHERE player_urtauth = ?";
        try (PreparedStatement pstmt = c.prepareStatement(sql)) {
            pstmt.setString(1, player.getUrtauth());
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String gametype = rs.getString("gametype");
                    int spree = rs.getInt("spree");
                    Gametype gt = logic.getGametypeByString(gametype);
                    if (gt != null) {
                        player.spree.put(gt, spree);
                    }
                }
            }
        }
    }

    public Map<Player, Integer> getTopSpreeAllTime(Gametype gametype, int number) {
        Map<Player, Integer> topSpree = new LinkedHashMap<Player, Integer>();
        try {
            String sql = "SELECT * FROM spree WHERE gametype = ? ORDER BY personal_best DESC LIMIT ?";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, gametype.getName());
            pstmt.setInt(2, number);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                String urtauth = rs.getString("player_urtauth");
                int spree = rs.getInt("personal_best");
                Player p = Player.get(urtauth);
                topSpree.put(p, spree);
            }
            rs.close();
            pstmt.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
        return topSpree;
    }

    public Map<Player, Integer> getWorstSpreeAllTime(Gametype gametype, int number) {
        Map<Player, Integer> worstSpree = new LinkedHashMap<Player, Integer>();
        try {
            String sql = "SELECT * FROM spree WHERE gametype = ? ORDER BY personal_worst ASC LIMIT ?";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, gametype.getName());
            pstmt.setInt(2, number);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                String urtauth = rs.getString("player_urtauth");
                int spree = rs.getInt("personal_worst") * -1;
                Player p = Player.get(urtauth);
                worstSpree.put(p, spree);
            }
            rs.close();
            pstmt.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
        return worstSpree;
    }

    public Map<Player, Integer> getTopSpree(Gametype gametype, int number) {
        Map<Player, Integer> topSpree = new LinkedHashMap<Player, Integer>();
        try {
            String sql = "SELECT * FROM spree WHERE gametype = ? and spree >= 0 ORDER BY spree DESC LIMIT ?";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, gametype.getName());
            pstmt.setInt(2, number);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                String urtauth = rs.getString("player_urtauth");
                int spree = rs.getInt("spree");
                Player p = Player.get(urtauth);
                topSpree.put(p, spree);
            }
            rs.close();
            pstmt.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
        return topSpree;
    }

    public Map<Player, Integer> getWorstSpree(Gametype gametype, int number) {
        Map<Player, Integer> worstSpree = new LinkedHashMap<Player, Integer>();
        try {
            String sql = "SELECT * FROM spree WHERE gametype = ? and spree <= 0 ORDER BY spree ASC LIMIT ?";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, gametype.getName());
            pstmt.setInt(2, number);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                String urtauth = rs.getString("player_urtauth");
                int spree = rs.getInt("spree") * -1;
                Player p = Player.get(urtauth);
                worstSpree.put(p, spree);
            }
            rs.close();
            pstmt.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
        return worstSpree;
    }

    private boolean columnExists(String table, String column) {
        try {
            ResultSet rs = c.createStatement().executeQuery("PRAGMA table_info(" + table + ")");
            while (rs.next()) {
                if (rs.getString("name").equals(column)) {
                    rs.close();
                    return true;
                }
            }
            rs.close();
        } catch (SQLException e) {
            log.warn("Exception checking column existence: ", e);
        }
        return false;
    }
}
