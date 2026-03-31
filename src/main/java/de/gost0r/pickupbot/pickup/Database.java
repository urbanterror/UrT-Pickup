package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.discord.DiscordChannel;
import de.gost0r.pickupbot.discord.DiscordEmbed;
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
    private Map<String, PreparedStatement> preparedStmtCache;


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
            }
            initTable();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
    }

    public void disconnect() {
        try {
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
                    + "active TEXT )";
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

            sql = "CREATE TABLE IF NOT EXISTS bets (ID INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "player_userid TEXT,"
                    + "player_urtauth TEXT,"
                    + "matchid INTEGER,"
                    + "team INTEGER," // red = 0   blue = 1
                    + "won TEXT,"
                    + "amount INTEGER,"
                    + "odds FLOAT,"
                    + "FOREIGN KEY (matchid) REFERENCES match(ID), "
                    + "FOREIGN KEY (player_userid, player_urtauth) REFERENCES player(userid, urtauth) )";
            stmt.executeUpdate(sql);

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
        } catch (SQLException e) {
            log.warn("Exception: ", e);
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

    public int createMatch(Match match) {
        try {
            String sql = "INSERT INTO match (state, gametype, server, starttime, map, elo_red, elo_blue) VALUES (?, ?, ?, ?, ?, ?, ?)";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, match.getMatchState().name());
            pstmt.setString(2, match.getGametype().getName());
            pstmt.setInt(3, match.getServer().id);
            pstmt.setLong(4, match.getStartTime());
            pstmt.setString(5, match.getMap().name);
            pstmt.setInt(6, match.getEloRed());
            pstmt.setInt(7, match.getEloBlue());
            pstmt.executeUpdate();
            pstmt.close();

            Statement stmt = c.createStatement();
            sql = "SELECT ID FROM match ORDER BY ID DESC";
            ResultSet rs = stmt.executeQuery(sql);
            rs.next();
            int mid = rs.getInt("id");
            for (Player player : match.getPlayerList()) {
                int[] score = new int[2];
                for (int i = 0; i < score.length; ++i) {
                    sql = "INSERT INTO score (kills, deaths) VALUES (0, 0)";
                    stmt.executeUpdate(sql);
                    sql = "SELECT ID FROM score ORDER BY ID DESC";
                    rs = stmt.executeQuery(sql);
                    rs.next();
                    score[i] = rs.getInt("ID");
                }
                sql = "INSERT INTO player_in_match (matchid, player_userid, player_urtauth, team) VALUES (?, ?, ?, ?)";
                pstmt = c.prepareStatement(sql);
                pstmt.setInt(1, mid);
                pstmt.setString(2, player.getDiscordUser().getId());
                pstmt.setString(3, player.getUrtauth());
                pstmt.setString(4, match.getTeam(player));
                pstmt.executeUpdate();
                pstmt.close();
                sql = "SELECT ID FROM player_in_match ORDER BY ID DESC";
                rs = stmt.executeQuery(sql);
                rs.next();
                int pidmid = rs.getInt("ID");
                sql = "INSERT INTO stats (pim, ip, score_1, score_2, status) VALUES (?, null, ?, ?, ?)";
                pstmt = c.prepareStatement(sql);
                pstmt.setInt(1, pidmid);
                pstmt.setInt(2, score[0]);
                pstmt.setInt(3, score[1]);
                pstmt.setString(4, match.getStats(player).getStatus().name());
                pstmt.executeUpdate();
                pstmt.close();
            }
            stmt.close();
            rs.close();
            return mid;
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
        return -1;
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
            String sql = "SELECT gametype, teamsize, active FROM gametype";
            ResultSet rs = stmt.executeQuery(sql);
            while (rs.next()) {
                Gametype gametype = new Gametype(rs.getString("gametype"), rs.getInt("teamsize"), Boolean.parseBoolean(rs.getString("active")), false);
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
                        permissionService);
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
     * Build a match embed directly from a single SQL query, bypassing Player/Match object
     * construction entirely. Used by !last where we only need display data.
     * Returns null if the match ID is invalid.
     */
    public DiscordEmbed loadMatchEmbed(int matchId) {
        try {
            String sql = "SELECT m.starttime, m.map, m.gametype, m.score_red, m.score_blue, m.state, m.server,"
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

            // Match-level fields from first row
            boolean matchFound = false;
            String matchMap = null, matchGametype = null, matchStateStr = null;
            int matchScoreRed = 0, matchScoreBlue = 0, matchServer = 0;
            long matchStarttime = 0;

            StringBuilder redPlayers = new StringBuilder();
            StringBuilder redScores = new StringBuilder();
            StringBuilder bluePlayers = new StringBuilder();
            StringBuilder blueScores = new StringBuilder();

            while (rs.next()) {
                if (!matchFound) {
                    matchFound = true;
                    matchStarttime = rs.getLong("starttime");
                    matchMap = rs.getString("map");
                    matchGametype = rs.getString("gametype");
                    matchScoreRed = rs.getInt("score_red");
                    matchScoreBlue = rs.getInt("score_blue");
                    matchStateStr = rs.getString("state");
                    matchServer = rs.getInt("server");
                }

                String urtauth = rs.getString("player_urtauth");
                if (urtauth != null) {
                    String team = rs.getString("team");
                    String country = rs.getString("country");
                    if (country == null || country.equalsIgnoreCase("NOT_DEFINED")) {
                        country = "<:puma:849287183474884628>";
                    } else {
                        country = ":flag_" + country.toLowerCase() + ":";
                    }
                    String playerRow = country + " \u200b \u200b " + urtauth + "\n";
                    String scoreRow = rs.getInt("total_kills") + "/" + rs.getInt("total_deaths") + "/" + rs.getInt("total_assists") + "\n";

                    if ("red".equals(team)) {
                        redPlayers.append(playerRow);
                        redScores.append(scoreRow);
                    } else {
                        bluePlayers.append(playerRow);
                        blueScores.append(scoreRow);
                    }
                }
            }
            rs.close();
            pstmt.close();

            if (!matchFound) return null;

            // Resolve gametype/server/map from in-memory caches
            Gametype gametype = logic.getGametypeByString(matchGametype);
            Server server = logic.getServerByID(matchServer);
            GameMap map = logic.getMapByName(matchMap);

            DiscordEmbed embed = new DiscordEmbed();

            String regionFlag = ":globe_with_meridians:";
            if (server != null) {
                regionFlag = server.getRegionFlag(logic.getDynamicServers() || (gametype != null && gametype.getTeamSize() == 0), true);
            }
            embed.setTitle(regionFlag + " Match #" + matchId);
            embed.setColor(7056881);

            if (map != null && gametype != null) {
                String mapName = "**" + gametype.getName() + "** - " + map.name + " (" + map.getDiscordDownloadLink() + ")";
                if (gametype.getPrivate()) {
                    embed.setDescription(":lock: " + mapName);
                } else {
                    embed.setDescription(mapName);
                }
            }

            if (gametype != null && gametype.getTeamSize() != 0) {
                embed.addField("<:rush_red:510982162263179275> \u200b \u200b " + matchScoreRed + "\n \u200b", redPlayers.toString(), true);
                embed.addField("K/D/A\n \u200b", redScores.toString(), true);
                embed.addField("\u200b", "\u200b", false);
            }

            embed.addField("<:rush_blue:510067909628788736> \u200b \u200b " + matchScoreBlue + "\n \u200b", bluePlayers.toString(), true);
            embed.addField("K/D/A\n \u200b", blueScores.toString(), true);

            embed.setTimestamp(matchStarttime);
            embed.setFooterText(matchStateStr);

            return embed;
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
        return null;
    }

    /**
     * Get the embed for the most recent match. Single query, no Player objects.
     */
    public DiscordEmbed loadLastMatchEmbed() {
        try {
            String sql = "SELECT MAX(ID) FROM match";
            PreparedStatement pstmt = getPreparedStatement(sql);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                int id = rs.getInt(1);
                rs.close();
                if (id > 0) {
                    return loadMatchEmbed(id);
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
     * Get the embed for a player's most recent match. Single query, no Player objects.
     * Joins against match table to skip orphaned player_in_match rows.
     */
    public DiscordEmbed loadLastMatchPlayerEmbed(String urtauth) {
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
                return loadMatchEmbed(matchId);
            }
            rs.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
        return null;
    }

    public Player loadPlayer(String urtauth) {
        return loadPlayer(null, urtauth, true);
    }

    public Player loadPlayer(DiscordUser user) {
        return loadPlayer(user, null, true);
    }

    // can load inactive users
    public Player loadPlayer(DiscordUser user, String urtauth, boolean onlyActive) {
        Player player = null;
        try {
            String sql = "SELECT * FROM player WHERE userid LIKE ? AND urtauth LIKE ? AND active LIKE ?";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, user == null ? "%" : user.getId());
            pstmt.setString(2, urtauth == null ? "%" : urtauth);
            pstmt.setString(3, onlyActive ? String.valueOf(true) : "%");
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                player = new Player(discordService.getUserById(rs.getString("userid")), rs.getString("urtauth"));
                player.setElo(rs.getInt("elo"));
                player.setEloChange(rs.getInt("elochange"));
                player.setActive(Boolean.parseBoolean(rs.getString("active")));
                player.setEnforceAC(Boolean.parseBoolean(rs.getString("enforce_ac")));
                player.setCountry(rs.getString("country"));
                player.setCoins(rs.getLong("coins"));
                player.setEloBoost(rs.getLong("eloboost"));
                player.setAdditionalMapVotes(rs.getInt("mapvote"));
                player.setMapBans(rs.getInt("mapban"));
                player.setProctf(Boolean.parseBoolean(rs.getString("proctf")));
                loadSpree(player);

                sql = "SELECT start, end, reason, pardon, forgiven FROM banlist WHERE player_userid=? AND player_urtauth=?";
                PreparedStatement banstmt = c.prepareStatement(sql);
                banstmt.setString(1, player.getDiscordUser().getId());
                banstmt.setString(2, player.getUrtauth());
                ResultSet banSet = banstmt.executeQuery();
                while (banSet.next()) {
                    PlayerBan ban = new PlayerBan();
                    ban.player = player;
                    ban.startTime = banSet.getLong("start");
                    ban.endTime = banSet.getLong("end");
                    ban.reason = BanReason.valueOf(banSet.getString("reason"));
                    ban.pardon = banSet.getString("pardon").matches("^[0-9]*$") ? discordService.getUserById(banSet.getString("pardon")) : null;
                    ban.forgiven = banSet.getBoolean("forgiven");
                    player.addBan(ban);
                }
                player.setRank(getRankForPlayer(player));
                player.stats = getPlayerStats(player, logic.currentSeason);
                banSet.close();
                banstmt.close();
            }
            rs.close();
            pstmt.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
        return player;
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

    public void saveMatch(Match match) {
        try {
            ResultSet rs;
            String sql = "UPDATE match SET state=?, score_red=?, score_blue=? WHERE id=?";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, match.getMatchState().name());
            pstmt.setInt(2, match.getScoreRed());
            pstmt.setInt(3, match.getScoreBlue());
            pstmt.setInt(4, match.getID());
            pstmt.executeUpdate();

            for (Player player : match.getPlayerList()) {
                // get ids
                sql = "SELECT ID FROM player_in_match WHERE matchid=? AND player_userid=? AND player_urtauth=?";
                pstmt = c.prepareStatement(sql);
                pstmt.setInt(1, match.getID());
                pstmt.setString(2, player.getDiscordUser().getId());
                pstmt.setString(3, player.getUrtauth());
                rs = pstmt.executeQuery();
                rs.next();
                int pim = rs.getInt("ID");

                sql = "SELECT score_1, score_2 FROM stats WHERE pim=?";
                pstmt = c.prepareStatement(sql);
                pstmt.setInt(1, pim);
                rs = pstmt.executeQuery();
                rs.next();
                int[] scoreid = new int[]{rs.getInt("score_1"), rs.getInt("score_2")};

                // update ip & status (leaver etc)
                sql = "UPDATE stats SET ip=?, status=? WHERE pim=?";
                pstmt = c.prepareStatement(sql);
                pstmt.setString(1, match.getStats(player).getIP());
                pstmt.setString(2, match.getStats(player).getStatus().name());
                pstmt.setInt(3, pim);
                pstmt.executeUpdate();

                // update playerscore
                sql = "UPDATE score SET kills=?, deaths=?, assists=?, caps=?, returns=?, fckills=?, stopcaps=?, protflag=? WHERE ID=?";
                pstmt = c.prepareStatement(sql);
                for (int i = 0; i < 2; ++i) {
                    pstmt.setInt(1, match.getStats(player).score[i].score);
                    pstmt.setInt(2, match.getStats(player).score[i].deaths);
                    pstmt.setInt(3, match.getStats(player).score[i].assists);
                    pstmt.setInt(4, match.getStats(player).score[i].caps);
                    pstmt.setInt(5, match.getStats(player).score[i].returns);
                    pstmt.setInt(6, match.getStats(player).score[i].fc_kills);
                    pstmt.setInt(7, match.getStats(player).score[i].stop_caps);
                    pstmt.setInt(8, match.getStats(player).score[i].protect_flag);
                    pstmt.setInt(9, scoreid[i]);
                    pstmt.executeUpdate();
                }

                // update elo change
                sql = "UPDATE player SET elo=?, elochange=? WHERE userid=? AND urtauth=?";
                pstmt = c.prepareStatement(sql);
                pstmt.setInt(1, player.getElo());
                pstmt.setInt(2, player.getEloChange());
                pstmt.setString(3, player.getDiscordUser().getId());
                pstmt.setString(4, player.getUrtauth());
                pstmt.executeUpdate();
                pstmt.close();
                rs.close();
            }
        } catch (SQLException e) {
            log.warn("Exception: ", e);
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

    public void removePlayer(Player player) {
        try {
            String sql = "UPDATE player SET active=? WHERE userid=? AND urtauth=?";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, String.valueOf(false));
            pstmt.setString(2, player.getDiscordUser().getId());
            pstmt.setString(3, player.getUrtauth());
            pstmt.executeUpdate();
            pstmt.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
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
        int rank = -1;
        try {
            String sql = "SELECT (SELECT COUNT(*) FROM player b WHERE a.elo < b.elo AND active=?) AS rank FROM player a WHERE userid=? AND urtauth=?";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, String.valueOf(true));
            pstmt.setString(2, player.getDiscordUser().getId());
            pstmt.setString(3, player.getUrtauth());
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                rank = rs.getInt("rank") + 1;
            }
            rs.close();
            pstmt.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
        return rank;
    }

    public WinDrawLoss getWDLForPlayer(Player player, Gametype gt, Season season) {
        WinDrawLoss wdl = new WinDrawLoss();
        if (gt == null) {
            return wdl;
        }
        try {
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
            PreparedStatement pstmt = c.prepareStatement(sql);

            int paramIndex = 1;
            if (!gt.getName().equals("TS")) {
                pstmt.setString(paramIndex++, gt.getName());
            }
            pstmt.setLong(paramIndex++, season.startdate);
            pstmt.setLong(paramIndex++, season.enddate);
            pstmt.setString(paramIndex++, player.getUrtauth());
            pstmt.setString(paramIndex, player.getDiscordUser().getId());
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                wdl.win = rs.getInt("win");
                wdl.draw = rs.getInt("draw");
                wdl.loss = rs.getInt("loss");
            }
            rs.close();
            pstmt.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
        return wdl;
    }

    public int getWDLRankForPlayer(Player player, Gametype gt, Season season) {
        int rank = -1;
        if (gt == null) {
            return rank;
        }
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

            String sql = "WITH tablewdl (urtauth, matchcount, winrate) AS (SELECT urtauth, COUNT(urtauth) as matchcount, (CAST(SUM(CASE WHEN stat.myscore > stat.oppscore THEN 1 ELSE 0 END) AS FLOAT)+ CAST(SUM(CASE WHEN stat.myscore = stat.oppscore THEN 1 ELSE 0 END) AS FLOAT)/2)/(CAST(SUM(CASE WHEN stat.myscore > stat.oppscore THEN 1 ELSE 0 END) AS FLOAT)+ CAST(SUM(CASE WHEN stat.myscore = stat.oppscore THEN 1 ELSE 0 END) AS FLOAT) + CAST(SUM(CASE WHEN stat.myscore < stat.oppscore THEN 1 ELSE 0 END) AS FLOAT)) as winrate FROM (SELECT pim.player_urtauth AS urtauth, (CASE WHEN pim.team = 'red' THEN m.score_red ELSE m.score_blue END) AS myscore, (CASE WHEN pim.team = 'blue' THEN m.score_red ELSE m.score_blue END) AS oppscore FROM 'player_in_match' AS pim JOIN 'match' AS m ON m.id = pim.matchid JOIN 'player' AS p ON pim.player_urtauth=p.urtauth AND pim.player_userid=p.userid AND p.active='true'   WHERE (m.state = 'Done' OR m.state = 'Surrender' OR m.state = 'Mercy') AND m.starttime > ? AND m.starttime < ? " + gametypeCondition + ") AS stat GROUP BY urtauth HAVING COUNT(urtauth) > ? ORDER BY winrate DESC) SELECT ( SELECT COUNT(*) + 1  FROM tablewdl  WHERE winrate > t.winrate) as rowIndex FROM tablewdl t WHERE urtauth = ?";
            PreparedStatement pstmt = c.prepareStatement(sql);

            int paramIndex = 1;
            pstmt.setLong(paramIndex++, season.startdate);
            pstmt.setLong(paramIndex++, season.enddate);
            if (!gt.getName().equals("TS")) {
                pstmt.setString(paramIndex++, gt.getName());
            }
            pstmt.setInt(paramIndex++, limit);
            pstmt.setString(paramIndex, player.getUrtauth());
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                rank = rs.getInt("rowIndex");
            }
            rs.close();
            pstmt.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
        return rank;
    }

    public int getKDRRankForPlayer(Player player, Gametype gt, Season season) {
        int rank = -1;
        if (gt == null) {
            return -1;
        }
        try {
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

            String sql = "WITH tablekdr (auth, matchcount, kdr) AS (SELECT player.urtauth AS auth, COUNT(player_in_match.player_urtauth)/2 as matchcount, " + rating_query + " AS kdr FROM (score INNER JOIN stats ON stats.score_1 = score.ID OR stats.score_2 = score.ID INNER JOIN player_in_match ON player_in_match.ID = stats.pim  INNER JOIN player ON player_in_match.player_userid = player.userid INNER JOIN match ON player_in_match.matchid = match.id)  WHERE player.active = 'true' AND (match.state = 'Done' OR match.state = 'Surrender' OR match.state = 'Mercy') " + gametypeCondition + " AND match.starttime > ? AND match.starttime < ? GROUP BY player_in_match.player_urtauth HAVING matchcount > ? ORDER BY kdr DESC) SELECT ( SELECT COUNT(*) + 1  FROM tablekdr  WHERE kdr > t.kdr) as rowIndex FROM tablekdr t WHERE auth = ?";
            PreparedStatement pstmt = c.prepareStatement(sql);

            int paramIndex = 1;
            if (!gt.getName().equals("TS")) {
                pstmt.setString(paramIndex++, gt.getName());
            }
            pstmt.setLong(paramIndex++, season.startdate);
            pstmt.setLong(paramIndex++, season.enddate);
            pstmt.setInt(paramIndex++, limit);
            pstmt.setString(paramIndex, player.getUrtauth());

            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                rank = rs.getInt("rowIndex");
            }
            rs.close();
            pstmt.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
        return rank;
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

            String sql = "SELECT urtauth, COUNT(urtauth) as matchcount, SUM(CASE WHEN stat.myscore > stat.oppscore THEN 1 ELSE 0 END) as win, SUM(CASE WHEN stat.myscore = stat.oppscore THEN 1 ELSE 0 END) as draw, SUM(CASE WHEN stat.myscore < stat.oppscore THEN 1 ELSE 0 END) loss , (CAST(SUM(CASE WHEN stat.myscore > stat.oppscore THEN 1 ELSE 0 END) AS FLOAT)+ CAST(SUM(CASE WHEN stat.myscore = stat.oppscore THEN 1 ELSE 0 END) AS FLOAT)/2)/(CAST(SUM(CASE WHEN stat.myscore > stat.oppscore THEN 1 ELSE 0 END) AS FLOAT)+ CAST(SUM(CASE WHEN stat.myscore = stat.oppscore THEN 1 ELSE 0 END) AS FLOAT) + CAST(SUM(CASE WHEN stat.myscore < stat.oppscore THEN 1 ELSE 0 END) AS FLOAT)) as winrate FROM (SELECT pim.player_urtauth AS urtauth, (CASE WHEN pim.team = 'red' THEN m.score_red ELSE m.score_blue END) AS myscore, (CASE WHEN pim.team = 'blue' THEN m.score_red ELSE m.score_blue END) AS oppscore FROM 'player_in_match' AS pim JOIN 'match' AS m ON m.id = pim.matchid JOIN 'player' AS p ON pim.player_urtauth=p.urtauth AND pim.player_userid=p.userid AND p.active='true'   WHERE (m.state = 'Done' OR m.state = 'Surrender' OR m.state = 'Mercy') " + gametypeCondition + " AND m.starttime > ? AND m.starttime < ?) AS stat GROUP BY urtauth HAVING COUNT(urtauth) > ? ORDER BY winrate DESC LIMIT ?";
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
            // TODO: maybe move this somewhere
            String sql = "SELECT SUM(kills) as sumkills, SUM(deaths) as sumdeaths, SUM(assists) as sumassists FROM score INNER JOIN stats ON stats.score_1 = score.ID OR stats.score_2 = score.ID INNER JOIN player_in_match ON player_in_match.ID = stats.pim INNER JOIN match ON match.id = player_in_match.matchid WHERE (match.gametype=\"TS\" OR match.gametype=\"PROMOD\") AND (match.state = 'Done' OR match.state = 'Surrender' OR match.state = 'Mercy') AND player_userid=? AND player_urtauth=? AND match.starttime > ? AND match.starttime < ?;";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, player.getDiscordUser().getId());
            pstmt.setString(2, player.getUrtauth());
            pstmt.setLong(3, season.startdate);
            pstmt.setLong(4, season.enddate);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                float kdr = ((float) rs.getInt("sumkills") + (float) rs.getInt("sumassists") / 2) / (float) rs.getInt("sumdeaths");
                player.setKdr(kdr);
                stats.kdr = kdr;
                stats.kills = rs.getInt("sumkills");
                stats.assists = rs.getInt("sumassists");
                stats.deaths = rs.getInt("sumdeaths");
            }

            // CTF
            sql = "SELECT COUNT(player_in_match.player_urtauth)/2 as matchcount, CAST (SUM(score.kills) AS FLOAT) / (COUNT(player_in_match.player_urtauth)/2 ) / 50   as ctfrating, SUM(caps) as sumcaps, SUM(returns) as sumreturns, SUM(fckills) as sumfckills, SUM(stopcaps) as sumstopcaps, SUM(protflag) as sumprotflag, player_in_match.player_urtauth as auth, match.id as matchid FROM score INNER JOIN stats ON (score.id = stats.score_1 OR score.id = stats.score_2) INNER JOIN player_in_match ON player_in_match.id = stats.pim INNER JOIN match ON player_in_match.matchid = match.id WHERE match.gametype=\"CTF\" AND (match.state = 'Done' OR match.state = 'Surrender' OR match.state = 'Mercy') AND auth=?  AND match.starttime > ? AND match.starttime < ?;";
            pstmt = c.prepareStatement(sql);
            pstmt.setString(1, player.getUrtauth());
            pstmt.setLong(2, season.startdate);
            pstmt.setLong(3, season.enddate);
            rs = pstmt.executeQuery();
            if (rs.next()) {
                stats.ctf_rating = rs.getFloat("ctfrating");
                stats.caps = rs.getInt("sumcaps");
                stats.returns = rs.getInt("sumreturns");
                stats.fckills = rs.getInt("sumfckills");
                stats.stopcaps = rs.getInt("sumstopcaps");
                stats.protflag = rs.getInt("sumprotflag");
            }
            rs.close();
            pstmt.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }

        return stats;
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
        try {
            String sql = "SELECT number, startdate, enddate FROM season ORDER BY number DESC LIMIT 1;";
            PreparedStatement pstmt = getPreparedStatement(sql);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                int number = rs.getInt("number");
                long startdate = rs.getLong("startdate");
                long enddate = rs.getLong("enddate");
                return new Season(number, startdate, enddate);
            }
            rs.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
        return null;
    }

    public Season getSeason(int number) {
        try {
            String sql = "SELECT number, startdate, enddate FROM season WHERE number = ?;";
            PreparedStatement pstmt = getPreparedStatement(sql);
            pstmt.setString(1, String.valueOf(number));
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                long startdate = rs.getLong("startdate");
                long enddate = rs.getLong("enddate");
                return new Season(number, startdate, enddate);
            }
            rs.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
        return null;
    }

    public void updatePlayerCoins(Player player) {
        try {
            String sql = "UPDATE player SET coins=? WHERE userid=? AND urtauth=?";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setLong(1, player.getCoins());
            pstmt.setString(2, player.getDiscordUser().getId());
            pstmt.setString(3, player.getUrtauth());
            pstmt.executeUpdate();
            pstmt.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
        }
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

    public void createBet(Bet bet) {
        try {
            String sql = "INSERT INTO bets (player_userid, player_urtauth, matchid, team, won, amount, odds) VALUES (?, ?, ?, ?, ?, ?, ?)";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, bet.player.getDiscordUser().getId());
            pstmt.setString(2, bet.player.getUrtauth());
            pstmt.setInt(3, bet.matchid);
            pstmt.setInt(4, bet.color.equals("red") ? 0 : 1);
            pstmt.setString(5, String.valueOf(bet.won));
            pstmt.setLong(6, bet.amount);
            pstmt.setFloat(7, bet.odds);
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
            String sql = "SELECT * from bets WHERE bets.player_urtauth = ? ORDER BY bets.ID DESC LIMIT 10;";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, p.getUrtauth());
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                int matchid = rs.getInt("matchid");
                String color = rs.getInt("team") == 0 ? "red" : "blue";
                int amount = rs.getInt("amount");
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
            String sql = "SELECT * FROM spree WHERE player_urtauth = ?";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, player.getUrtauth());
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                String gametype = rs.getString("gametype");
                int spree = rs.getInt("spree");
                Gametype gt = logic.getGametypeByString(gametype);
                if (gt != null) {
                    player.spree.put(gt, spree);
                }
            }
            rs.close();
            pstmt.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
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
}
