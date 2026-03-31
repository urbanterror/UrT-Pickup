package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.discord.*;
import de.gost0r.pickupbot.ftwgl.FtwglApi;
import de.gost0r.pickupbot.permission.PermissionService;
import de.gost0r.pickupbot.permission.PickupRoleCache;
import de.gost0r.pickupbot.pickup.server.Server;
import org.junit.jupiter.api.*;

import java.io.File;
import java.lang.reflect.Field;
import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for PickupLogic command methods.
 * Uses a real SQLite DB (temp file) with seeded data, and mocked externals.
 */
class PickupLogicTest {

    static PickupLogic logic;
    static FtwglApi ftw;
    static DiscordService discord;
    static PermissionService perms;
    static Database db;

    // Seeded players, keyed by urtauth
    static Map<String, Player> players = new LinkedHashMap<>();
    static Map<String, DiscordUser> users = new LinkedHashMap<>();

    @BeforeAll
    static void setup() throws Exception {
        resetPlayerCache();

        // -- Mocks --
        discord = mock(DiscordService.class);
        when(discord.getMe()).thenReturn(mockUser("999", "Bot"));

        ftw = mock(FtwglApi.class);
        when(ftw.hasLauncherOn(any())).thenReturn(true);
        when(ftw.checkIfPingStored(any())).thenReturn(true);
        when(ftw.getPlayerRatings(any(Player.class))).thenReturn(0f);
        when(ftw.getPlayerRatings(anyList())).thenReturn(Map.of());
        when(ftw.getTopPlayerRatings()).thenReturn(Collections.emptyMap());
        when(ftw.requestPingUrl(any())).thenReturn("https://test/ping");

        perms = mock(PermissionService.class);
        PickupRoleCache roleCache = new PickupRoleCache();

        // -- Channels --
        DiscordChannel pubCh = mockChannel("9001", "pickup");
        DiscordChannel admCh = mockChannel("9002", "admin");
        when(discord.getChannelById("9001")).thenReturn(pubCh);
        when(discord.getChannelById("9002")).thenReturn(admCh);

        // -- Seed DB to temp file --
        File tmp = File.createTempFile("pickuptest", "");
        tmp.delete(); // we just want the path prefix
        tmp.deleteOnExit();
        String envPrefix = tmp.getAbsolutePath();
        new File(envPrefix + ".pickup.db").deleteOnExit();

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + envPrefix + ".pickup.db")) {
            seed(c);
        }

        // -- Wire up bot + logic --
        PickupBot bot = new PickupBot(envPrefix, ftw, discord, perms, roleCache);
        logic = new PickupLogic(bot, ftw, discord, perms, roleCache);
        logic.init();
        db = logic.db;

        // Pre-load players into cache
        for (String auth : new String[]{
                "alpha", "bravo", "charlie", "delta", "echo",
                "foxtrot", "golf", "hotel", "india", "juliet"}) {
            Player p = Player.get(auth);
            if (p != null) players.put(auth, p);
        }
    }

    @AfterAll
    static void teardown() {
        if (db != null) db.disconnect();
        resetPlayerCache();
    }

    @BeforeEach
    void resetQueues() {
        // Clear all queues between tests so they don't bleed
        logic.cmdReset("cur");
        logic.cmdUnlock();
    }

    // ========== !status ==========

    @Test void status_empty_showsNobody() {
        var r = logic.cmdStatus();
        assertContains(r, "Nobody signed up");
    }

    @Test void status_afterAdd_showsPlayer() {
        logic.cmdAddPlayer(players.get("alpha"), gt("TS"), false);
        assertContains(logic.cmdStatus(), "alpha");
    }

    // ========== !add ==========

    @Test void add_putsPlayerInQueue() {
        logic.cmdAddPlayer(players.get("bravo"), gt("TS"), false);
        assertContains(logic.cmdStatus(), "bravo");
    }

    @Test void add_twice_saysAlreadyQueued() {
        var ts = gt("TS");
        logic.cmdAddPlayer(players.get("charlie"), ts, false);
        var r = logic.cmdAddPlayer(players.get("charlie"), ts, false);
        assertContains(r, "already queued");
    }

    @Test void add_multipleGametype() {
        logic.cmdAddPlayer(players.get("delta"), gt("TS"), false);
        logic.cmdAddPlayer(players.get("delta"), gt("CTF"), false);
        var s = logic.cmdStatus().getMessage();
        assertTrue(s.contains("delta"), "Should be in both queues");
    }

    @Test void add_locked_rejected() {
        logic.cmdLock();
        var r = logic.cmdAddPlayer(players.get("echo"), gt("TS"), false);
        assertContains(r, "lock");
    }

    @Test void add_forced_bypassesLock() {
        logic.cmdLock();
        var r = logic.cmdAddPlayer(players.get("foxtrot"), gt("TS"), true);
        // Should NOT contain lock message
        if (r.getMessage() != null) {
            assertFalse(r.getMessage().toLowerCase().contains("lock"));
        }
    }

    @Test void add_banned_rejected() {
        var p = players.get("golf");
        var ban = new PlayerBan();
        ban.player = p;
        ban.startTime = System.currentTimeMillis() - 1000;
        ban.endTime = System.currentTimeMillis() + 3_600_000;
        ban.reason = PlayerBan.BanReason.NOSHOW;
        ban.forgiven = false;
        p.addBan(ban);
        db.createBan(ban);

        var r = logic.cmdAddPlayer(p, gt("TS"), false);
        assertNotNull(r.getMessage(), "Banned player should get a message");
    }

    // ========== FTW mock checks ==========

    @Test void add_launcherOff_rejected() {
        var p = players.get("hotel");
        when(ftw.hasLauncherOn(p)).thenReturn(false);
        var r = logic.cmdAddPlayer(p, gt("TS"), false);
        assertEquals(Config.pkup_launcheroff, r.getMessage());
        // Reset mock
        when(ftw.hasLauncherOn(p)).thenReturn(true);
    }

    @Test void add_noPing_asksPingUrl() {
        var p = players.get("india");
        when(ftw.checkIfPingStored(p)).thenReturn(false);
        logic.cmdAddPlayer(p, gt("TS"), false);
        verify(ftw).requestPingUrl(p);
        // Reset mock
        when(ftw.checkIfPingStored(p)).thenReturn(true);
    }

    // ========== !remove ==========

    @Test void remove_all_clearsPlayer() {
        logic.cmdAddPlayer(players.get("alpha"), gt("TS"), false);
        logic.cmdRemovePlayer(players.get("alpha"), null);
        var s = logic.cmdStatus().getMessage();
        assertFalse(s.contains("alpha"), "Player should be gone after remove");
    }

    @Test void remove_specificGt_keepsOther() {
        var p = players.get("bravo");
        logic.cmdAddPlayer(p, gt("TS"), false);
        logic.cmdAddPlayer(p, gt("CTF"), false);
        logic.cmdRemovePlayer(p, List.of(gt("TS")));
        var s = logic.cmdStatus().getMessage();
        // Should still be in CTF
        assertTrue(s.contains("bravo") && s.contains("CTF"));
    }

    // ========== !register ==========

    @Test void register_new_succeeds() {
        var u = mockUser("3001", "newbie");
        when(discord.getUserById("3001")).thenReturn(u);
        var msg = mock(DiscordMessage.class);

        var r = logic.cmdRegisterPlayer(u, "newbieauth", msg);
        assertEquals(Config.auth_success, r.getMessage());
    }

    @Test void register_duplicateUser_fails() {
        var u = users.get("alpha");
        var msg = mock(DiscordMessage.class);
        var r = logic.cmdRegisterPlayer(u, "whatever", msg);
        assertEquals(Config.auth_taken_user, r.getMessage());
    }

    @Test void register_duplicateAuth_fails() {
        var u = mockUser("3002", "imposter");
        when(discord.getUserById("3002")).thenReturn(u);
        var msg = mock(DiscordMessage.class);
        var r = logic.cmdRegisterPlayer(u, "alpha", msg);
        assertEquals(Config.auth_taken_urtauth, r.getMessage());
    }

    @Test void register_invalidChars_fails() {
        var u = mockUser("3003", "badname");
        when(discord.getUserById("3003")).thenReturn(u);
        var msg = mock(DiscordMessage.class);
        var r = logic.cmdRegisterPlayer(u, "BAD_NAME!", msg);
        assertEquals(Config.auth_invalid, r.getMessage());
    }

    // ========== !top10 ==========

    @Test void topElo_returnsEmbed() {
        var r = logic.cmdTopElo(10);
        assertNotNull(r.getEmbed(), "top10 should return an embed");
    }

    // ========== !country ==========

    @Test void country_unregistered_rejected() {
        var u = mockUser("4001", "ghost");
        var r = logic.cmdSetPlayerCountry(u, "DE");
        assertEquals(Config.user_not_registered, r.getMessage());
    }

    // ========== !surrender ==========

    @Test void surrender_notInMatch_rejected() {
        var r = logic.cmdSurrender(players.get("alpha"));
        assertEquals(Config.player_not_in_match, r.getMessage());
    }

    // ========== !wallet / !donate ==========

    @Test void wallet_showsBalance() {
        var r = logic.cmdWallet(players.get("alpha"));
        assertNotNull(r.getMessage());
        assertTrue(r.getMessage().contains("alpha"));
    }

    @Test void donate_transfersCoins() {
        var from = players.get("charlie");
        var to = players.get("delta");
        long before = from.getCoins();
        logic.cmdDonate(from, to, 100);
        assertEquals(before - 100, from.getCoins());
    }

    @Test void donate_overLimit_rejected() {
        var r = logic.cmdDonate(players.get("echo"), players.get("foxtrot"), 20_000);
        assertEquals(Config.donate_above_limit, r.getMessage());
    }

    @Test void donate_insufficientFunds_rejected() {
        var r = logic.cmdDonate(players.get("india"), players.get("juliet"), 5_000);
        assertEquals(Config.bets_insufficient, r.getMessage());
    }

    // ========== pardonPlayer (slash command) ==========

    @Test void pardon_slashCommand_bannedByBot_pardons() {
        var p = players.get("alpha");
        addBotBan(p, PlayerBan.BanReason.NOSHOW);
        assertTrue(p.isBannedByBot(), "Player should be banned by bot before pardon");

        var interaction = mock(DiscordSlashCommandInteraction.class);
        var admin = players.get("bravo");
        logic.pardonPlayer(interaction, p, "forgiven", admin);

        assertFalse(p.isBannedByBot(), "Player should no longer be banned by bot after pardon");
        verify(interaction).deleteDeferredReply();
        verify(interaction, never()).respondEphemeral(anyString());
    }

    @Test void pardon_slashCommand_notBanned_respondsEphemeral() {
        var p = players.get("bravo");
        assertFalse(p.isBannedByBot(), "Player should not be banned");

        var interaction = mock(DiscordSlashCommandInteraction.class);
        var admin = players.get("charlie");
        logic.pardonPlayer(interaction, p, "test", admin);

        verify(interaction).respondEphemeral(contains("not banned"));
        verify(interaction, never()).deleteDeferredReply();
    }

    @Test void pardon_slashCommand_manualBan_notPardoned() {
        var p = players.get("charlie");
        addBotBan(p, PlayerBan.BanReason.INSULT);
        assertTrue(p.isBanned(), "Player should be banned");
        assertFalse(p.isBannedByBot(), "INSULT ban is not a bot ban");

        var interaction = mock(DiscordSlashCommandInteraction.class);
        var admin = players.get("delta");
        logic.pardonPlayer(interaction, p, "test", admin);

        assertTrue(p.isBanned(), "Manual ban should remain");
        verify(interaction).respondEphemeral(contains("!unban"));
        verify(interaction, never()).deleteDeferredReply();
        // Clean up manual ban so it doesn't affect other tests
        p.forgiveBan();
    }

    @Test void pardon_slashCommand_ragequitBan_pardons() {
        var p = players.get("delta");
        addBotBan(p, PlayerBan.BanReason.RAGEQUIT);
        assertTrue(p.isBannedByBot(), "RAGEQUIT should count as bot ban");

        var interaction = mock(DiscordSlashCommandInteraction.class);
        var admin = players.get("echo");
        logic.pardonPlayer(interaction, p, "cool down", admin);

        assertFalse(p.isBannedByBot(), "RAGEQUIT ban should be pardoned");
        verify(interaction).deleteDeferredReply();
    }

    // ========== pardonPlayer (text command / channel) ==========

    @Test void pardon_channel_bannedByBot_pardons() {
        var p = players.get("echo");
        addBotBan(p, PlayerBan.BanReason.NOSHOW);
        assertTrue(p.isBannedByBot(), "Player should be banned by bot");

        var admin = players.get("foxtrot");
        logic.pardonPlayer(logic.getChannelByType(PickupChannelType.ADMIN), p, "text pardon", admin);

        assertFalse(p.isBannedByBot(), "Player should be pardoned via text command");
    }

    @Test void pardon_channel_notBanned_sendsMessage() {
        var p = players.get("foxtrot");
        assertFalse(p.isBannedByBot(), "Player should not be banned");

        var admin = players.get("alpha");
        // Should not throw; sends "not banned" to admin channel
        logic.pardonPlayer(logic.getChannelByType(PickupChannelType.ADMIN), p, "test", admin);

        assertFalse(p.isBannedByBot(), "Player state unchanged");
    }

    @Test void pardon_channel_multiplePlayers_pardonsAll() {
        var p1 = players.get("hotel");
        var p2 = players.get("india");
        addBotBan(p1, PlayerBan.BanReason.NOSHOW);
        addBotBan(p2, PlayerBan.BanReason.RAGEQUIT);
        assertTrue(p1.isBannedByBot(), "p1 should be banned");
        assertTrue(p2.isBannedByBot(), "p2 should be banned");

        var admin = players.get("juliet");
        var channels = logic.getChannelByType(PickupChannelType.ADMIN);
        logic.pardonPlayer(channels, p1, "batch pardon", admin);
        logic.pardonPlayer(channels, p2, "batch pardon", admin);

        assertFalse(p1.isBannedByBot(), "p1 should be pardoned");
        assertFalse(p2.isBannedByBot(), "p2 should be pardoned");
    }

    @Test void pardon_channel_mixedBans_onlyBotBanPardoned() {
        var p = players.get("juliet");
        addBotBan(p, PlayerBan.BanReason.NOSHOW);
        addBotBan(p, PlayerBan.BanReason.INSULT);
        assertTrue(p.isBannedByBot(), "Should have bot ban");
        assertTrue(p.isBanned(), "Should also have manual ban");

        var admin = players.get("alpha");
        logic.pardonPlayer(logic.getChannelByType(PickupChannelType.ADMIN), p, "partial pardon", admin);

        assertFalse(p.isBannedByBot(), "Bot ban should be pardoned");
        assertTrue(p.isBanned(), "Manual INSULT ban should remain");
        // Clean up manual ban
        p.forgiveBan();
    }

    // ========== pardon helpers ==========

    /** Creates and persists an active ban for the given player. */
    static void addBotBan(Player p, PlayerBan.BanReason reason) {
        var ban = new PlayerBan();
        ban.player = p;
        ban.startTime = System.currentTimeMillis() - 1000;
        ban.endTime = System.currentTimeMillis() + 3_600_000; // 1 hour from now
        ban.reason = reason;
        ban.forgiven = false;
        p.addBan(ban);
        db.createBan(ban);
    }
    
    // ========== Match captain selection ==========

    @Test void tsSortPlayers_prefersFtwRatingsForCaptainsWhenEnoughRatingsExist() throws Exception {
        var india = players.get("india");
        var echo = players.get("echo");
        var hotel = players.get("hotel");
        var charlie = players.get("charlie");

        when(ftw.getPlayerRatings(anyList())).thenAnswer(invocation -> {
            List<Player> requestedPlayers = invocation.getArgument(0);
            Map<Player, Float> ratings = new HashMap<>();
            for (Player player : requestedPlayers) {
                if (player.equals(india)) {
                    ratings.put(player, 2100f);
                } else if (player.equals(echo)) {
                    ratings.put(player, 1900f);
                }
            }
            return ratings;
        });

        Match match = buildTsCaptainMatch();
        match.sortPlayers();

        assertTrue(match.getPlayerList().contains(hotel), "Match should include unrated players");
        assertTrue(match.getPlayerList().contains(charlie), "Match should include unrated players");
        assertEquals(india, match.getTeamRed().get(0), "Highest FTW-rated player should be red captain");
        assertEquals(echo, match.getTeamBlue().get(0), "Second-highest FTW-rated player should be blue captain");
        assertNotEquals(hotel, match.getTeamRed().get(0), "Unrated player should not become captain when rated players exist");
        assertNotEquals(hotel, match.getTeamBlue().get(0), "Unrated player should not become captain when rated players exist");
        assertNotEquals(charlie, match.getTeamRed().get(0), "Unrated player should not become captain when rated players exist");
        assertNotEquals(charlie, match.getTeamBlue().get(0), "Unrated player should not become captain when rated players exist");
        assertEquals(echo, match.getCaptainsTurn(), "Blue captain should pick first");

        when(ftw.getPlayerRatings(anyList())).thenReturn(Map.of());
    }

    @Test void tsSortPlayers_fallsBackToCaptainScoreWhenTooFewFtwRatingsExist() throws Exception {
        var india = players.get("india");
        var hotel = players.get("hotel");
        var charlie = players.get("charlie");

        when(ftw.getPlayerRatings(anyList())).thenAnswer(invocation -> {
            List<Player> requestedPlayers = invocation.getArgument(0);
            Map<Player, Float> ratings = new HashMap<>();
            for (Player player : requestedPlayers) {
                if (player.equals(india)) {
                    ratings.put(player, 2500f);
                }
            }
            return ratings;
        });

        Match match = buildTsCaptainMatch();
        match.sortPlayers();

        assertEquals(hotel, match.getTeamRed().get(0), "Fallback captain selection should use local captain score");
        assertEquals(charlie, match.getTeamBlue().get(0), "Fallback captain selection should ignore a lone FTW rating");

        when(ftw.getPlayerRatings(anyList())).thenReturn(Map.of());
    }

    // ========== Helpers ==========

    static Gametype gt(String name) { return logic.getGametypeByString(name); }

    static Match buildTsCaptainMatch() throws Exception {
        Match match = new Match(logic, gt("TS"), List.of(logic.getMapByName("ut4_turnpike")), perms);

        Server server = new Server(999, "127.0.0.1", 27960, "rcon", "pw", true, Region.EU);
        server.country = "DE";
        server.city = "Berlin";
        for (Player player : players.values()) {
            server.playerPing.put(player, 50);
        }

        Map<Player, MatchStats> playerStats = new LinkedHashMap<>();
        for (Player player : players.values()) {
            playerStats.put(player, new MatchStats());
        }

        setField(match, "server", server);
        setField(match, "playerStats", playerStats);

        return match;
    }

    static void assertContains(PickupReply r, String sub) {
        assertNotNull(r.getMessage(), "Reply message was null");
        assertTrue(r.getMessage().toLowerCase().contains(sub.toLowerCase()),
                "Expected '" + sub + "' in: " + r.getMessage());
    }

    /** Simple DiscordUser stub that supports equals/hashCode by ID. */
    static DiscordUser mockUser(String id, String name) {
        return new DiscordUser() {
            public String getId() { return id; }
            public String getUsername() { return name; }
            public String getMentionString() { return "<@" + id + ">"; }
            public String getAvatarUrl() { return "https://cdn.test/" + id; }
            public boolean isInGuild(String guildId) { return true; }
            public void sendPrivateMessage(String msg) {}
            public void sendPrivateMessage(String msg, DiscordEmbed e, java.util.List<DiscordComponent> c) {}
            public boolean equals(Object o) { return o instanceof DiscordUser du && id.equals(du.getId()); }
            public int hashCode() { return id.hashCode(); }
        };
    }

    static DiscordChannel mockChannel(String id, String name) {
        var ch = mock(DiscordChannel.class);
        when(ch.getId()).thenReturn(id);
        when(ch.getName()).thenReturn(name);
        when(ch.isThreadChannel()).thenReturn(false);
        when(ch.isPrivateChannel()).thenReturn(false);
        return ch;
    }

    /** Seeds the DB with season, gametypes, maps, servers, channels, and 10 players. */
    static void seed(Connection c) throws Exception {
        var s = c.createStatement();
        // Tables
        s.executeUpdate("CREATE TABLE IF NOT EXISTS player (userid TEXT,urtauth TEXT,elo INTEGER DEFAULT 1000,elochange INTEGER DEFAULT 0,active TEXT,country TEXT,enforce_ac TEXT DEFAULT 'true',coins INTEGER DEFAULT 1000,eloboost INTEGER DEFAULT 0,mapvote INTEGER DEFAULT 0,mapban INTEGER DEFAULT 0,proctf TEXT DEFAULT 'true',PRIMARY KEY(userid,urtauth))");
        s.executeUpdate("CREATE TABLE IF NOT EXISTS gametype (gametype TEXT PRIMARY KEY,teamsize INTEGER,active TEXT)");
        s.executeUpdate("CREATE TABLE IF NOT EXISTS map (map TEXT,gametype TEXT,active TEXT,banned_until INTEGER DEFAULT 0,PRIMARY KEY(map,gametype))");
        s.executeUpdate("CREATE TABLE IF NOT EXISTS banlist (ID INTEGER PRIMARY KEY AUTOINCREMENT,player_userid TEXT,player_urtauth TEXT,reason TEXT,start INTEGER,end INTEGER,pardon TEXT,forgiven BOOLEAN)");
        s.executeUpdate("CREATE TABLE IF NOT EXISTS report (ID INTEGER PRIMARY KEY AUTOINCREMENT,player_userid TEXT,player_urtauth TEXT,reporter_userid TEXT,reporter_urtauth TEXT,reason TEXT,match INTEGER)");
        s.executeUpdate("CREATE TABLE IF NOT EXISTS match (ID INTEGER PRIMARY KEY AUTOINCREMENT,server INTEGER,gametype TEXT,state TEXT,starttime INTEGER,map TEXT,elo_red INTEGER,elo_blue INTEGER,score_red INTEGER DEFAULT 0,score_blue INTEGER DEFAULT 0)");
        s.executeUpdate("CREATE TABLE IF NOT EXISTS player_in_match (ID INTEGER PRIMARY KEY AUTOINCREMENT,matchid INTEGER,player_userid TEXT,player_urtauth TEXT,team TEXT)");
        s.executeUpdate("CREATE TABLE IF NOT EXISTS score (ID INTEGER PRIMARY KEY AUTOINCREMENT,kills INTEGER DEFAULT 0,deaths INTEGER DEFAULT 0,assists INTEGER DEFAULT 0,caps INTEGER DEFAULT 0,returns INTEGER DEFAULT 0,fckills INTEGER DEFAULT 0,stopcaps INTEGER DEFAULT 0,protflag INTEGER DEFAULT 0)");
        s.executeUpdate("CREATE TABLE IF NOT EXISTS stats (pim INTEGER PRIMARY KEY,ip TEXT,status TEXT,score_1 INTEGER,score_2 INTEGER)");
        s.executeUpdate("CREATE TABLE IF NOT EXISTS server (ID INTEGER PRIMARY KEY AUTOINCREMENT,ip TEXT,port INTEGER,rcon TEXT,password TEXT,active TEXT,region TEXT)");
        s.executeUpdate("CREATE TABLE IF NOT EXISTS roles (role TEXT,type TEXT,PRIMARY KEY(role))");
        s.executeUpdate("CREATE TABLE IF NOT EXISTS channels (channel TEXT,type TEXT,PRIMARY KEY(channel,type))");
        s.executeUpdate("CREATE TABLE IF NOT EXISTS season (number INTEGER,startdate INTEGER,enddate INTEGER,PRIMARY KEY(number))");
        s.executeUpdate("CREATE TABLE IF NOT EXISTS bets (ID INTEGER PRIMARY KEY AUTOINCREMENT,player_userid TEXT,player_urtauth TEXT,matchid INTEGER,team INTEGER,won TEXT,amount INTEGER,odds FLOAT)");
        s.executeUpdate("CREATE TABLE IF NOT EXISTS spree (ID INTEGER PRIMARY KEY AUTOINCREMENT,player_userid TEXT,player_urtauth TEXT,gametype TEXT,spree INTEGER DEFAULT 0,personal_best INTEGER DEFAULT 0,personal_worst INTEGER DEFAULT 0)");

        // Season
        s.executeUpdate("INSERT INTO season VALUES(1,1704067200000,1767225600000)");

        // Gametypes
        s.executeUpdate("INSERT INTO gametype VALUES('TS',5,'true')");
        s.executeUpdate("INSERT INTO gametype VALUES('CTF',5,'true')");

        // Maps
        for (var m : new String[]{"ut4_turnpike","ut4_abbey","ut4_casa","ut4_uptown","ut4_algiers"})
            s.executeUpdate("INSERT INTO map VALUES('" + m + "','TS','true',0)");
        for (var m : new String[]{"ut4_riyadh","ut4_sanchez","ut4_tohunga_b8"})
            s.executeUpdate("INSERT INTO map VALUES('" + m + "','CTF','true',0)");

        // Servers
        s.executeUpdate("INSERT INTO server VALUES(1,'192.168.1.1',27960,'rcon1','pw1','true','EU')");
        s.executeUpdate("INSERT INTO server VALUES(2,'192.168.1.2',27960,'rcon2','pw2','true','NA')");

        // Channels
        s.executeUpdate("INSERT INTO channels VALUES('9001','PUBLIC')");
        s.executeUpdate("INSERT INTO channels VALUES('9002','ADMIN')");

        // Players (10, varied elo)
        String[][] pp = {
                {"1001","alpha","1200","US"}, {"1002","bravo","1150","DE"},
                {"1003","charlie","1300","FR"}, {"1004","delta","1050","BR"},
                {"1005","echo","950","AU"},  {"1006","foxtrot","1100","GB"},
                {"1007","golf","1250","SE"}, {"1008","hotel","1400","PL"},
                {"1009","india","800","CA"}, {"1010","juliet","1000","IT"},
        };
        var ps = c.prepareStatement("INSERT INTO player(userid,urtauth,elo,elochange,active,country) VALUES(?,?,?,0,'true',?)");
        for (var p : pp) {
            ps.setString(1, p[0]);
            ps.setString(2, p[1]);
            ps.setInt(3, Integer.parseInt(p[2]));
            ps.setString(4, p[3]);
            ps.executeUpdate();
            // Register mock user with discord service
            var u = mockUser(p[0], p[1]);
            when(discord.getUserById(p[0])).thenReturn(u);
            users.put(p[1], u);
        }
        ps.close();
        s.close();
    }

    static void resetPlayerCache() {
        try {
            Field f = Player.class.getDeclaredField("playerList");
            f.setAccessible(true);
            ((List<?>) f.get(null)).clear();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
