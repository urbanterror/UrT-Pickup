package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.discord.DiscordChannel;
import de.gost0r.pickupbot.discord.DiscordRole;
import de.gost0r.pickupbot.discord.DiscordUser;
import de.gost0r.pickupbot.pickup.PlayerBan.BanReason;
import de.gost0r.pickupbot.pickup.server.Server;
import de.gost0r.pickupbot.pickup.stats.WinDrawLoss;
import io.sentry.Sentry;
import lombok.extern.slf4j.Slf4j;

import java.sql.*;
import java.util.*;

@Slf4j
public class Database {

    private Connection c = null;
    private final PickupLogic logic;
    private Map<String, PreparedStatement> preparedStmtCache;


    public Database(PickupLogic logic) {
        preparedStmtCache = new HashMap<>();
        this.logic = logic;
        initConnection();
    }

    private void initConnection() {
        try {
            c = DriverManager.getConnection("jdbc:sqlite:" + logic.bot.env + ".pickup.db");
            initTable();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
        }
    }

    public void disconnect() {
        try {
            c.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
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
                    + "FOREIGN KEY (gametype) REFERENCES gametype(gametype), "
                    + "FOREIGN KEY (player_userid, player_urtauth) REFERENCES player(userid, urtauth) )";
            stmt.executeUpdate(sql);

            stmt.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
        }
    }


    public void createPlayer(Player player) {
        try {
            // check whether user exists
            String sql = "SELECT * FROM player WHERE userid=? AND urtauth=?";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, player.getDiscordUser().id);
            pstmt.setString(2, player.getUrtauth());
            ResultSet rs = pstmt.executeQuery();
            if (!rs.next()) {
                sql = "INSERT INTO player (userid, urtauth, elo, elochange, active, country) VALUES (?, ?, ?, ?, ?, ?)";
                pstmt = c.prepareStatement(sql);
                pstmt.setString(1, player.getDiscordUser().id);
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
                pstmt.setString(2, player.getDiscordUser().id);
                pstmt.setString(3, player.getUrtauth());
                pstmt.executeUpdate();
            }
            rs.close();
            pstmt.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
        }
    }

    public void createBan(PlayerBan ban) {
        try {
            String sql = "INSERT INTO banlist (player_userid, player_urtauth, start, end, reason, pardon, forgiven) VALUES (?, ?, ?, ?, ?, 'null', 0)";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, ban.player.getDiscordUser().id);
            pstmt.setString(2, ban.player.getUrtauth());
            pstmt.setLong(3, ban.startTime);
            pstmt.setLong(4, ban.endTime);
            pstmt.setString(5, ban.reason.name());
            pstmt.executeUpdate();
            pstmt.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
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
            Sentry.captureException(e);
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
            Sentry.captureException(e);
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
            Sentry.captureException(e);
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
            Sentry.captureException(e);
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
                pstmt.setString(2, player.getDiscordUser().id);
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
            Sentry.captureException(e);
        }
        return -1;
    }

    public int getLastMatchID() {
        try {
            Statement stmt = c.createStatement();
            String sql = "SELECT ID FROM match ORDER BY ID DESC";
            ResultSet rs = stmt.executeQuery(sql);
            rs.next();
            int id = rs.getInt("id");
            stmt.close();
            rs.close();
            return id;
        } catch (SQLException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
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
            Sentry.captureException(e);
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
                DiscordRole role = DiscordRole.getRole(rs.getString("role"));
                assert role != null;
                log.debug("loadRoles(): {} type={}", role.id, type.name());
                map.get(type).add(role);
            }

            stmt.close();
            rs.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
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
                DiscordChannel channel = DiscordChannel.findChannel(rs.getString("channel"));
                assert channel != null;
                map.get(type).add(channel);
                log.debug("loadChannels(): {} name={} type={}", channel.id, channel.name, type.name());
            }

            stmt.close();
            rs.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
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
            Sentry.captureException(e);
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
            Sentry.captureException(e);
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
            Sentry.captureException(e);
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
            Sentry.captureException(e);
        }
        return matchList;
    }

    public Match loadMatch(int id) {
        Match match = null;
        try {
            ResultSet rs, rs1, rs2, rs3;
            String sql = "SELECT starttime, map, gametype, score_red, score_blue, elo_red, elo_blue, state, server FROM match WHERE ID=?";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setInt(1, id);
            rs = pstmt.executeQuery();
            if (rs.next()) {

                Map<Player, MatchStats> stats = new HashMap<Player, MatchStats>();
                Map<String, List<Player>> teamList = new HashMap<String, List<Player>>();
                teamList.put("red", new ArrayList<Player>());
                teamList.put("blue", new ArrayList<Player>());

                // getting players in match
                sql = "SELECT ID, player_userid, player_urtauth, team FROM player_in_match WHERE matchid=?";
                pstmt = c.prepareStatement(sql);
                pstmt.setInt(1, id);
                rs1 = pstmt.executeQuery();

                while (rs1.next()) {
                    int pidmid = rs1.getInt("ID");
                    String userid = rs1.getString("player_userid"); // not needed as we potentially load the player via loadPlayer(urtauth)
                    String urtauth = rs1.getString("player_urtauth");
                    String team = rs1.getString("team");

                    // getting stats
                    sql = "SELECT ip, score_1, score_2, status FROM stats WHERE pim=?";
                    pstmt = c.prepareStatement(sql);
                    pstmt.setInt(1, pidmid);
                    rs2 = pstmt.executeQuery();
                    rs2.next();

                    int[] scoreid = new int[]{rs2.getInt("score_1"), rs2.getInt("score_2")};
                    String ip = rs2.getString("ip");
                    MatchStats.Status status = MatchStats.Status.valueOf(rs2.getString("status"));

                    Score[] scores = new Score[2];

                    // getting score
                    for (int i = 0; i < 2; ++i) {
                        sql = "SELECT kills, deaths, assists, caps, returns, fckills, stopcaps, protflag FROM score WHERE ID=? ORDER BY ID DESC";
                        pstmt = c.prepareStatement(sql);
                        pstmt.setInt(1, scoreid[i]);
                        rs3 = pstmt.executeQuery();
                        rs3.next();

                        scores[i] = new Score();
                        scores[i].score = rs3.getInt("kills");
                        scores[i].deaths = rs3.getInt("deaths");
                        scores[i].assists = rs3.getInt("assists");
                        scores[i].caps = rs3.getInt("caps");
                        scores[i].returns = rs3.getInt("returns");
                        scores[i].fc_kills = rs3.getInt("fckills");
                        scores[i].stop_caps = rs3.getInt("stopcaps");
                        scores[i].protect_flag = rs3.getInt("protflag");
                        rs3.close();
                    }

                    // assemble stats
                    Player player = Player.get(DiscordUser.getUser(userid), urtauth);
                    stats.put(player, new MatchStats(scores[0], scores[1], ip, status));
                    teamList.get(team).add(player);
                    rs2.close();
                }
                rs1.close();

                Gametype gametype = logic.getGametypeByString(rs.getString("gametype"));
                Server server = logic.getServerByID(rs.getInt("server"));
                GameMap map = logic.getMapByName(rs.getString("map"));
                MatchState state = MatchState.valueOf(rs.getString("state"));

                match = new Match(id, rs.getLong("starttime"),
                        map,
                        new int[]{rs.getInt("score_red"), rs.getInt("score_blue")},
                        new int[]{rs.getInt("elo_red"), rs.getInt("elo_blue")},
                        teamList,
                        state,
                        gametype,
                        server,
                        stats,
                        logic);
            }
            rs.close();
            pstmt.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
        }
        return match;
    }

    public Match loadLastMatch() {
        Match match = null;
        try {
            String sql = "SELECT * FROM match ORDER BY ID DESC LIMIT 1";
            PreparedStatement pstmt = getPreparedStatement(sql);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                match = loadMatch(rs.getInt("ID"));
            }
        } catch (SQLException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
        }
        return match;

    }

    public Match loadLastMatchPlayer(Player p) {
        Match match = null;
        try {
            String sql = "SELECT matchid FROM  player_in_match  WHERE player_urtauth = ? ORDER BY ID DESC LIMIT 1;";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, p.getUrtauth());
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                match = loadMatch(rs.getInt("matchid"));
            }
            rs.close();
            pstmt.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
        }
        return match;

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
            pstmt.setString(1, user == null ? "%" : user.id);
            pstmt.setString(2, urtauth == null ? "%" : urtauth);
            pstmt.setString(3, onlyActive ? String.valueOf(true) : "%");
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                player = new Player(DiscordUser.getUser(rs.getString("userid")), rs.getString("urtauth"));
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
                banstmt.setString(1, player.getDiscordUser().id);
                banstmt.setString(2, player.getUrtauth());
                ResultSet banSet = banstmt.executeQuery();
                while (banSet.next()) {
                    PlayerBan ban = new PlayerBan();
                    ban.player = player;
                    ban.startTime = banSet.getLong("start");
                    ban.endTime = banSet.getLong("end");
                    ban.reason = BanReason.valueOf(banSet.getString("reason"));
                    ban.pardon = banSet.getString("pardon").matches("^[0-9]*$") ? DiscordUser.getUser(banSet.getString("pardon")) : null;
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
            Sentry.captureException(e);
        }
        return player;
    }

    public void updatePlayerCountry(Player player, String country) {
        try {
            String sql = "UPDATE player SET country=? WHERE userid=?";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, country);
            pstmt.setString(2, player.getDiscordUser().id);
            pstmt.executeUpdate();
            pstmt.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
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
            Sentry.captureException(e);
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
            Sentry.captureException(e);
        }
    }

    public void updateChannel(DiscordChannel channel, PickupChannelType type) {
        try {
            String sql = "SELECT * FROM channels WHERE channel=?";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, channel.id);
            ResultSet rs = pstmt.executeQuery();
            if (!rs.next()) {
                sql = "INSERT INTO channels (channel) VALUES (?)";
                pstmt = c.prepareStatement(sql);
                pstmt.setString(1, channel.id);
                pstmt.executeUpdate();
            }
            sql = "UPDATE channels SET type=? WHERE channel=?";
            pstmt = c.prepareStatement(sql);
            pstmt.setString(1, type.name());
            pstmt.setString(2, channel.id);
            pstmt.executeUpdate();
            pstmt.close();
            rs.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
        }
    }

    public void updateRole(DiscordRole role, PickupRoleType type) {
        try {
            String sql = "SELECT * FROM roles WHERE role=?";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, role.id);
            ResultSet rs = pstmt.executeQuery();
            if (!rs.next()) {
                sql = "INSERT INTO roles (role) VALUES (?)";
                pstmt = c.prepareStatement(sql);
                pstmt.setString(1, role.id);
                pstmt.executeUpdate();
            }
            sql = "UPDATE roles SET type=? WHERE role=?";
            pstmt = c.prepareStatement(sql);
            pstmt.setString(1, type.name());
            pstmt.setString(2, role.id);
            pstmt.executeUpdate();
            pstmt.close();
            rs.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
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
                pstmt.setString(2, player.getDiscordUser().id);
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
                pstmt.setString(3, player.getDiscordUser().id);
                pstmt.setString(4, player.getUrtauth());
                pstmt.executeUpdate();
                pstmt.close();
                rs.close();
            }
        } catch (SQLException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
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
            Sentry.captureException(e);
        }
    }

    public void removePlayer(Player player) {
        try {
            String sql = "UPDATE player SET active=? WHERE userid=? AND urtauth=?";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, String.valueOf(false));
            pstmt.setString(2, player.getDiscordUser().id);
            pstmt.setString(3, player.getUrtauth());
            pstmt.executeUpdate();
            pstmt.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
        }
    }

    public void enforcePlayerAC(Player player) {
        try {
            String sql = "UPDATE player SET enforce_ac=? WHERE userid=? AND urtauth=?";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, String.valueOf(player.getEnforceAC()));
            pstmt.setString(2, player.getDiscordUser().id);
            pstmt.setString(3, player.getUrtauth());
            pstmt.executeUpdate();
            pstmt.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
        }
    }

    public void setProctfPlayer(Player player) {
        try {
            String sql = "UPDATE player SET proctf=? WHERE userid=? AND urtauth=?";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, String.valueOf(player.getProctf()));
            pstmt.setString(2, player.getDiscordUser().id);
            pstmt.setString(3, player.getUrtauth());
            pstmt.executeUpdate();
            pstmt.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
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
            Sentry.captureException(e);
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
            Sentry.captureException(e);
        }
        return list;
    }

    public int getRankForPlayer(Player player) {
        int rank = -1;
        try {
            String sql = "SELECT (SELECT COUNT(*) FROM player b WHERE a.elo < b.elo AND active=?) AS rank FROM player a WHERE userid=? AND urtauth=?";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, String.valueOf(true));
            pstmt.setString(2, player.getDiscordUser().id);
            pstmt.setString(3, player.getUrtauth());
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                rank = rs.getInt("rank") + 1;
            }
            rs.close();
            pstmt.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
        }
        return rank;
    }

    public WinDrawLoss getWDLForPlayer(Player player, Gametype gt, Season season) {
        WinDrawLoss wdl = new WinDrawLoss();
        try {
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
                    + "WHERE (m.state = 'Done' OR m.state = 'Surrender' OR m.state = 'Mercy') AND m.gametype=? AND m.starttime > ? AND m.starttime < ?"
                    + "AND p.urtauth=? AND p.userid=?) AS stat ";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, gt.getName());
            pstmt.setLong(2, season.startdate);
            pstmt.setLong(3, season.enddate);
            pstmt.setString(4, player.getUrtauth());
            pstmt.setString(5, player.getDiscordUser().id);
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
        try {
            int limit = 20;
            if (season.number == 0) {
                limit = 100;
            }
            if (gt.getName().equals("CTF")) {
                limit = 10;
            }
            String sql = "WITH tablewdl (urtauth, matchcount, winrate) AS (SELECT urtauth, COUNT(urtauth) as matchcount, (CAST(SUM(CASE WHEN stat.myscore > stat.oppscore THEN 1 ELSE 0 END) AS FLOAT)+ CAST(SUM(CASE WHEN stat.myscore = stat.oppscore THEN 1 ELSE 0 END) AS FLOAT)/2)/(CAST(SUM(CASE WHEN stat.myscore > stat.oppscore THEN 1 ELSE 0 END) AS FLOAT)+ CAST(SUM(CASE WHEN stat.myscore = stat.oppscore THEN 1 ELSE 0 END) AS FLOAT) + CAST(SUM(CASE WHEN stat.myscore < stat.oppscore THEN 1 ELSE 0 END) AS FLOAT)) as winrate FROM (SELECT pim.player_urtauth AS urtauth, (CASE WHEN pim.team = 'red' THEN m.score_red ELSE m.score_blue END) AS myscore, (CASE WHEN pim.team = 'blue' THEN m.score_red ELSE m.score_blue END) AS oppscore FROM 'player_in_match' AS pim JOIN 'match' AS m ON m.id = pim.matchid JOIN 'player' AS p ON pim.player_urtauth=p.urtauth AND pim.player_userid=p.userid AND p.active='true'   WHERE (m.state = 'Done' OR m.state = 'Surrender' OR m.state = 'Mercy') AND m.starttime > ? AND m.starttime < ? AND m.gametype = ?) AS stat GROUP BY urtauth HAVING COUNT(urtauth) > ? ORDER BY winrate DESC) SELECT ( SELECT COUNT(*) + 1  FROM tablewdl  WHERE winrate > t.winrate) as rowIndex FROM tablewdl t WHERE urtauth = ?";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setLong(1, season.startdate);
            pstmt.setLong(2, season.enddate);
            pstmt.setString(3, gt.getName());
            pstmt.setInt(4, limit);
            pstmt.setString(5, player.getUrtauth());
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
            String sql = "WITH tablekdr (auth, matchcount, kdr) AS (SELECT player.urtauth AS auth, COUNT(player_in_match.player_urtauth)/2 as matchcount, " + rating_query + " AS kdr FROM (score INNER JOIN stats ON stats.score_1 = score.ID OR stats.score_2 = score.ID INNER JOIN player_in_match ON player_in_match.ID = stats.pim  INNER JOIN player ON player_in_match.player_userid = player.userid INNER JOIN match ON player_in_match.matchid = match.id)  WHERE player.active = 'true' AND (match.state = 'Done' OR match.state = 'Surrender' OR match.state = 'Mercy') AND match.gametype=? AND match.starttime > ? AND match.starttime < ? GROUP BY player_in_match.player_urtauth HAVING matchcount > ? ORDER BY kdr DESC) SELECT ( SELECT COUNT(*) + 1  FROM tablekdr  WHERE kdr > t.kdr) as rowIndex FROM tablekdr t WHERE auth = ?";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, gt.getName());
            pstmt.setLong(2, season.startdate);
            pstmt.setLong(3, season.enddate);
            pstmt.setInt(4, limit);
            pstmt.setString(5, player.getUrtauth());
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
            String sql = "SELECT urtauth, COUNT(urtauth) as matchcount, SUM(CASE WHEN stat.myscore > stat.oppscore THEN 1 ELSE 0 END) as win, SUM(CASE WHEN stat.myscore = stat.oppscore THEN 1 ELSE 0 END) as draw, SUM(CASE WHEN stat.myscore < stat.oppscore THEN 1 ELSE 0 END) loss , (CAST(SUM(CASE WHEN stat.myscore > stat.oppscore THEN 1 ELSE 0 END) AS FLOAT)+ CAST(SUM(CASE WHEN stat.myscore = stat.oppscore THEN 1 ELSE 0 END) AS FLOAT)/2)/(CAST(SUM(CASE WHEN stat.myscore > stat.oppscore THEN 1 ELSE 0 END) AS FLOAT)+ CAST(SUM(CASE WHEN stat.myscore = stat.oppscore THEN 1 ELSE 0 END) AS FLOAT) + CAST(SUM(CASE WHEN stat.myscore < stat.oppscore THEN 1 ELSE 0 END) AS FLOAT)) as winrate FROM (SELECT pim.player_urtauth AS urtauth, (CASE WHEN pim.team = 'red' THEN m.score_red ELSE m.score_blue END) AS myscore, (CASE WHEN pim.team = 'blue' THEN m.score_red ELSE m.score_blue END) AS oppscore FROM 'player_in_match' AS pim JOIN 'match' AS m ON m.id = pim.matchid JOIN 'player' AS p ON pim.player_urtauth=p.urtauth AND pim.player_userid=p.userid AND p.active='true'   WHERE (m.state = 'Done' OR m.state = 'Surrender' OR m.state = 'Mercy') AND m.gametype = ? AND m.starttime > ? AND m.starttime < ?) AS stat GROUP BY urtauth HAVING COUNT(urtauth) > ? ORDER BY winrate DESC LIMIT ?";
            PreparedStatement pstmt = getPreparedStatement(sql);
            pstmt.setString(1, gt.getName());
            pstmt.setLong(2, season.startdate);
            pstmt.setLong(3, season.enddate);
            pstmt.setLong(4, limit);
            pstmt.setInt(5, number);
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
            String sql = "SELECT player.urtauth AS auth, COUNT(player_in_match.player_urtauth)/2 as matchcount, " + rating_query + " AS kdr FROM score INNER JOIN stats ON stats.score_1 = score.ID OR stats.score_2 = score.ID INNER JOIN player_in_match ON player_in_match.ID = stats.pim  INNER JOIN player ON player_in_match.player_userid = player.userid INNER JOIN match ON match.id = player_in_match.matchid WHERE player.active = \"true\" AND (match.state = 'Done' OR match.state = 'Surrender' OR match.state = 'Mercy') AND match.gametype = ? AND match.starttime > ? AND match.starttime < ? GROUP BY player_in_match.player_urtauth HAVING matchcount > ? ORDER BY kdr DESC LIMIT ?";
            PreparedStatement pstmt = getPreparedStatement(sql);
            pstmt.setString(1, gt.getName());
            pstmt.setLong(2, season.startdate);
            pstmt.setLong(3, season.enddate);
            pstmt.setLong(4, limit);
            pstmt.setInt(5, number);
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
            String sql = "SELECT SUM(kills) as sumkills, SUM(deaths) as sumdeaths, SUM(assists) as sumassists FROM score INNER JOIN stats ON stats.score_1 = score.ID OR stats.score_2 = score.ID INNER JOIN player_in_match ON player_in_match.ID = stats.pim INNER JOIN match ON match.id = player_in_match.matchid WHERE match.gametype=\"TS\" AND (match.state = 'Done' OR match.state = 'Surrender' OR match.state = 'Mercy') AND player_userid=? AND player_urtauth=? AND match.starttime > ? AND match.starttime < ?;";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, player.getDiscordUser().id);
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
            pstmt.setString(2, player.getDiscordUser().id);
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
            pstmt.setString(4, player.getDiscordUser().id);
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
            pstmt.setString(1, bet.player.getDiscordUser().id);
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
            Sentry.captureException(e);
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
            String sql = "INSERT INTO spree (player_userid, player_urtauth, gametype, spree, personal_best) VALUES (?, ?, ?, ?, ?)";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setString(1, player.getDiscordUser().id);
            pstmt.setString(2, player.getUrtauth());
            pstmt.setString(3, gametype.getName());
            pstmt.setInt(4, spree);
            pstmt.setInt(5, spree);
            pstmt.executeUpdate();
            pstmt.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
        }
    }

    public void updateSpree(Player player, Gametype gametype, int spree) {
        try {
            String sql = "UPDATE spree SET spree = ?, personal_best = CASE WHEN ? > personal_best THEN ? ELSE personal_best END WHERE player_urtauth = ? AND gametype = ?";
            PreparedStatement pstmt = c.prepareStatement(sql);
            pstmt.setInt(1, spree);
            pstmt.setInt(2, spree);
            pstmt.setInt(3, spree);
            pstmt.setString(4, player.getUrtauth());
            pstmt.setString(5, gametype.getName());
            pstmt.executeUpdate();
            pstmt.close();
        } catch (SQLException e) {
            log.warn("Exception: ", e);
            Sentry.captureException(e);
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
            Sentry.captureException(e);
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
            Sentry.captureException(e);
        }
        return topSpree;
    }

    public Map<Player, Integer> getTopSpree(Gametype gametype, int number) {
        Map<Player, Integer> topSpree = new LinkedHashMap<Player, Integer>();
        try {
            String sql = "SELECT * FROM spree WHERE gametype = ? ORDER BY spree DESC LIMIT ?";
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
            Sentry.captureException(e);
        }
        return topSpree;
    }
}
