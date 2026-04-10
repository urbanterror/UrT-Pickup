package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.discord.*;
import de.gost0r.pickupbot.ftwgl.FtwglApi;
import de.gost0r.pickupbot.permission.PermissionService;
import de.gost0r.pickupbot.permission.PickupRoleCache;
import org.junit.jupiter.api.*;

import java.io.File;
import java.lang.reflect.Field;
import java.sql.*;
import java.util.*;

import static org.mockito.Mockito.*;

/**
 * Tests that !forceadd, !reset, !lock, !unlock, and !remove are correctly
 * routed from both the public channel and the admin channel (including
 * super-admin DM).
 *
 * Uses the same real-SQLite + mocked-externals pattern as PickupLogicTest.
 */
class PickupBotCommandRoutingTest {

    static PickupBot bot;
    static PickupLogic logic;
    static DiscordService discord;
    static PermissionService perms;

    static DiscordChannel pubChannel;
    static DiscordChannel admChannel;
    static DiscordChannel dmChannel;

    static Map<String, Player> players = new LinkedHashMap<>();
    static Map<String, DiscordUser> users = new LinkedHashMap<>();

    @BeforeAll
    static void setup() throws Exception {
        resetPlayerCache();

        // -- Mocks --
        discord = mock(DiscordService.class);
        when(discord.getMe()).thenReturn(mockUser("999", "Bot"));

        FtwglApi ftw = mock(FtwglApi.class);
        when(ftw.hasLauncherOn(any())).thenReturn(true);
        when(ftw.checkIfPingStored(any())).thenReturn(true);
        when(ftw.getPlayerRatings(any(Player.class))).thenReturn(0f);
        when(ftw.getPlayerRatings(anyList())).thenReturn(Map.of());
        when(ftw.getTopPlayerRatings()).thenReturn(Collections.emptyMap());
        when(ftw.requestPingUrl(any())).thenReturn("https://test/ping");

        perms = mock(PermissionService.class);
        PickupRoleCache roleCache = new PickupRoleCache();

        // -- Channels --
        pubChannel = mockChannel("9001", "pickup", false, false);
        admChannel = mockChannel("9002", "admin", false, false);
        dmChannel = mockChannel("9003", "dm", true, false);
        when(discord.getChannelById("9001")).thenReturn(pubChannel);
        when(discord.getChannelById("9002")).thenReturn(admChannel);

        // -- Seed DB --
        File tmp = File.createTempFile("pickuprouting", "");
        tmp.delete();
        tmp.deleteOnExit();
        String envPrefix = tmp.getAbsolutePath();
        new File(envPrefix + ".pickup.db").deleteOnExit();

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + envPrefix + ".pickup.db")) {
            seed(c);
        }

        // -- Wire up bot + logic --
        bot = new PickupBot(envPrefix, ftw, discord, perms, roleCache);
        logic = new PickupLogic(bot, ftw, discord, perms, roleCache);
        logic.init();

        // Set private fields that are normally initialized by bot.init()
        Field selfField = PickupBot.class.getDeclaredField("self");
        selfField.setAccessible(true);
        selfField.set(bot, discord.getMe());

        Field logicField = PickupBot.class.getDeclaredField("logic");
        logicField.setAccessible(true);
        logicField.set(bot, logic);

        // Pre-load players
        for (String auth : new String[]{
                "alpha", "bravo", "charlie", "delta", "echo",
                "foxtrot", "golf", "hotel", "india", "juliet"}) {
            Player p = Player.get(auth);
            if (p != null) players.put(auth, p);
        }
    }

    @AfterAll
    static void teardown() {
        if (logic != null && logic.db != null) logic.db.disconnect();
        resetPlayerCache();
    }

    @BeforeEach
    void resetQueues() {
        logic.cmdReset("cur");
        logic.cmdUnlock();
        // Reset permission mocks to default (no rights)
        reset(perms);
    }

    // ========== !reset ==========

    @Test void reset_fromPublicChannel_withAdmin_works() {
        // Add a player to queue first
        logic.cmdAddPlayer(players.get("alpha"), gt("TS"), false);
        // Confirm they are in queue
        assertContains(logic.cmdStatus(), "alpha");

        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(true);
        sendCommand("!reset", users.get("alpha"), pubChannel);

        assertContains(logic.cmdStatus(), "Nobody signed up");
    }

    @Test void reset_fromAdminChannel_works() {
        logic.cmdAddPlayer(players.get("bravo"), gt("TS"), false);
        assertContains(logic.cmdStatus(), "bravo");

        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(true);
        when(perms.hasSuperAdminRights(users.get("alpha"))).thenReturn(false);
        sendCommand("!reset", users.get("alpha"), admChannel);

        assertContains(logic.cmdStatus(), "Nobody signed up");
    }

    @Test void reset_fromSuperAdminDM_works() {
        logic.cmdAddPlayer(players.get("charlie"), gt("TS"), false);
        assertContains(logic.cmdStatus(), "charlie");

        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(true);
        when(perms.hasSuperAdminRights(users.get("alpha"))).thenReturn(true);
        sendCommand("!reset", users.get("alpha"), dmChannel);

        assertContains(logic.cmdStatus(), "Nobody signed up");
    }

    @Test void reset_fromAdminChannel_nonAdmin_ignored() {
        logic.cmdAddPlayer(players.get("delta"), gt("TS"), false);

        when(perms.hasAdminRights(users.get("delta"))).thenReturn(false);
        when(perms.hasSuperAdminRights(users.get("delta"))).thenReturn(false);
        sendCommand("!reset", users.get("delta"), admChannel);

        // Queue should NOT be reset
        assertContains(logic.cmdStatus(), "delta");
    }

    @Test void reset_withGametype_fromAdminChannel_works() {
        logic.cmdAddPlayer(players.get("alpha"), gt("TS"), false);
        logic.cmdAddPlayer(players.get("alpha"), gt("CTF"), false);

        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(true);
        sendCommand("!reset TS", users.get("alpha"), admChannel);

        // CTF queue should still have the player, TS queue should be cleared
        String status = logic.cmdStatus().getMessage();
        org.junit.jupiter.api.Assertions.assertTrue(
                status.contains("CTF"), "CTF queue should still exist");
    }

    // ========== !lock / !unlock ==========

    @Test void lock_fromPublicChannel_withAdmin_works() {
        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(true);
        sendCommand("!lock", users.get("alpha"), pubChannel);

        var r = logic.cmdAddPlayer(players.get("bravo"), gt("TS"), false);
        assertContains(r, "lock");
    }

    @Test void lock_fromAdminChannel_works() {
        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(true);
        sendCommand("!lock", users.get("alpha"), admChannel);

        var r = logic.cmdAddPlayer(players.get("bravo"), gt("TS"), false);
        assertContains(r, "lock");
    }

    @Test void unlock_fromAdminChannel_works() {
        logic.cmdLock();
        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(true);
        sendCommand("!unlock", users.get("alpha"), admChannel);

        var r = logic.cmdAddPlayer(players.get("bravo"), gt("TS"), false);
        // Should NOT be rejected with lock message
        if (r.getMessage() != null) {
            org.junit.jupiter.api.Assertions.assertFalse(
                    r.getMessage().toLowerCase().contains("lock"),
                    "Queue should be unlocked");
        }
    }

    @Test void lock_fromSuperAdminDM_works() {
        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(true);
        when(perms.hasSuperAdminRights(users.get("alpha"))).thenReturn(true);
        sendCommand("!lock", users.get("alpha"), dmChannel);

        var r = logic.cmdAddPlayer(players.get("bravo"), gt("TS"), false);
        assertContains(r, "lock");
    }

    @Test void lock_fromPublicChannel_nonAdmin_ignored() {
        when(perms.hasAdminRights(users.get("delta"))).thenReturn(false);
        sendCommand("!lock", users.get("delta"), pubChannel);

        // Queue should NOT be locked
        var r = logic.cmdAddPlayer(players.get("bravo"), gt("TS"), false);
        if (r.getMessage() != null) {
            org.junit.jupiter.api.Assertions.assertFalse(
                    r.getMessage().toLowerCase().contains("lock"),
                    "Non-admin lock should be ignored");
        }
    }

    // ========== !forceadd ==========

    @Test void forceadd_fromPublicChannel_withAdmin_works() {
        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(true);
        when(discord.getUserFromMention("<@1002>")).thenReturn(users.get("bravo"));
        sendCommand("!forceadd TS <@1002>", users.get("alpha"), pubChannel);

        assertContains(logic.cmdStatus(), "bravo");
    }

    @Test void forceadd_fromAdminChannel_works() {
        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(true);
        when(discord.getUserFromMention("<@1003>")).thenReturn(users.get("charlie"));
        sendCommand("!forceadd TS <@1003>", users.get("alpha"), admChannel);

        assertContains(logic.cmdStatus(), "charlie");
    }

    @Test void forceadd_fromSuperAdminDM_works() {
        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(true);
        when(perms.hasSuperAdminRights(users.get("alpha"))).thenReturn(true);
        when(discord.getUserFromMention("<@1004>")).thenReturn(users.get("delta"));
        sendCommand("!forceadd TS <@1004>", users.get("alpha"), dmChannel);

        assertContains(logic.cmdStatus(), "delta");
    }

    @Test void forceadd_fromPublicChannel_nonAdmin_rejected() {
        when(perms.hasAdminRights(users.get("delta"))).thenReturn(false);
        DiscordMessage msg = mockMessage("!forceadd TS <@1002>", users.get("delta"), pubChannel);
        bot.recvMessage(msg);

        verify(msg).reply(Config.player_not_admin);
    }

    @Test void forceadd_wrongArgs_showsUsage() {
        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(true);
        DiscordMessage msg = mockMessage("!forceadd TS", users.get("alpha"), admChannel);
        bot.recvMessage(msg);

        verify(msg).reply(Config.wrong_argument_amount.replace(".cmd.", Config.USE_CMD_FORCEADD));
    }

    @Test void forceadd_bypassesLock() {
        logic.cmdLock();
        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(true);
        when(discord.getUserFromMention("<@1005>")).thenReturn(users.get("echo"));
        sendCommand("!forceadd TS <@1005>", users.get("alpha"), admChannel);

        assertContains(logic.cmdStatus(), "echo");
    }

    // ========== !remove ==========

    @Test void remove_fromPublicChannel_selfRemove() {
        logic.cmdAddPlayer(players.get("alpha"), gt("TS"), false);
        assertContains(logic.cmdStatus(), "alpha");

        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(false);
        sendCommand("!remove", users.get("alpha"), pubChannel);

        String status = logic.cmdStatus().getMessage();
        org.junit.jupiter.api.Assertions.assertFalse(
                status.contains("alpha"), "Player should be removed from queue");
    }

    @Test void remove_fromPublicChannel_adminRemovesOther() {
        logic.cmdAddPlayer(players.get("bravo"), gt("TS"), false);
        assertContains(logic.cmdStatus(), "bravo");

        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(true);
        when(discord.getUserFromMention("<@1002>")).thenReturn(users.get("bravo"));
        sendCommand("!remove <@1002>", users.get("alpha"), pubChannel);

        String status = logic.cmdStatus().getMessage();
        org.junit.jupiter.api.Assertions.assertFalse(
                status.contains("bravo"), "Admin should be able to remove another player");
    }

    @Test void remove_fromAdminChannel_removesPlayer() {
        logic.cmdAddPlayer(players.get("charlie"), gt("TS"), false);
        assertContains(logic.cmdStatus(), "charlie");

        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(true);
        when(discord.getUserFromMention("<@1003>")).thenReturn(users.get("charlie"));
        sendCommand("!remove <@1003>", users.get("alpha"), admChannel);

        String status = logic.cmdStatus().getMessage();
        org.junit.jupiter.api.Assertions.assertFalse(
                status.contains("charlie"), "Admin should remove player from admin channel");
    }

    @Test void remove_fromSuperAdminDM_removesPlayer() {
        logic.cmdAddPlayer(players.get("delta"), gt("TS"), false);
        assertContains(logic.cmdStatus(), "delta");

        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(true);
        when(perms.hasSuperAdminRights(users.get("alpha"))).thenReturn(true);
        when(discord.getUserFromMention("<@1004>")).thenReturn(users.get("delta"));
        sendCommand("!remove <@1004>", users.get("alpha"), dmChannel);

        String status = logic.cmdStatus().getMessage();
        org.junit.jupiter.api.Assertions.assertFalse(
                status.contains("delta"), "Super-admin should remove player via DM");
    }

    @Test void remove_fromAdminChannel_specificGametype() {
        logic.cmdAddPlayer(players.get("echo"), gt("TS"), false);
        logic.cmdAddPlayer(players.get("echo"), gt("CTF"), false);

        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(true);
        when(discord.getUserFromMention("<@1005>")).thenReturn(users.get("echo"));
        sendCommand("!remove <@1005> TS", users.get("alpha"), admChannel);

        String status = logic.cmdStatus().getMessage();
        // Should still be in CTF
        org.junit.jupiter.api.Assertions.assertTrue(
                status.contains("echo") && status.contains("CTF"),
                "Player should remain in CTF queue");
    }

    @Test void remove_fromAdminChannel_nonAdmin_ignored() {
        logic.cmdAddPlayer(players.get("foxtrot"), gt("TS"), false);

        when(perms.hasAdminRights(users.get("delta"))).thenReturn(false);
        when(perms.hasSuperAdminRights(users.get("delta"))).thenReturn(false);
        sendCommand("!remove <@1006>", users.get("delta"), admChannel);

        // Player should still be in queue (command ignored for non-admin in admin channel)
        assertContains(logic.cmdStatus(), "foxtrot");
    }

    // ========== !baninfo ==========

    @Test void baninfo_fromPublicChannel_self() {
        DiscordMessage msg = mockMessage("!baninfo", users.get("alpha"), pubChannel);
        bot.recvMessage(msg);

        // Should reply with ban info for self (alpha)
        verify(msg).reply(logic.printBanInfo(players.get("alpha")));
    }

    @Test void baninfo_fromPublicChannel_otherPlayer() {
        when(discord.getUserFromMention("<@1002>")).thenReturn(users.get("bravo"));
        DiscordMessage msg = mockMessage("!baninfo <@1002>", users.get("alpha"), pubChannel);
        bot.recvMessage(msg);

        verify(msg).reply(logic.printBanInfo(players.get("bravo")));
    }

    @Test void baninfo_fromAdminChannel_lookupByMention() {
        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(true);
        when(discord.getUserFromMention("<@1003>")).thenReturn(users.get("charlie"));
        DiscordMessage msg = mockMessage("!baninfo <@1003>", users.get("alpha"), admChannel);
        bot.recvMessage(msg);

        verify(msg).reply(logic.printBanInfo(players.get("charlie")));
    }

    @Test void baninfo_fromAdminChannel_lookupByUrtauth() {
        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(true);
        DiscordMessage msg = mockMessage("!baninfo delta", users.get("alpha"), admChannel);
        bot.recvMessage(msg);

        verify(msg).reply(logic.printBanInfo(players.get("delta")));
    }

    @Test void baninfo_fromSuperAdminDM_works() {
        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(true);
        when(perms.hasSuperAdminRights(users.get("alpha"))).thenReturn(true);
        when(discord.getUserFromMention("<@1005>")).thenReturn(users.get("echo"));
        DiscordMessage msg = mockMessage("!baninfo <@1005>", users.get("alpha"), dmChannel);
        bot.recvMessage(msg);

        verify(msg).reply(logic.printBanInfo(players.get("echo")));
    }

    @Test void baninfo_fromAdminChannel_noArgs_unregistered() {
        // From admin channel with no args and null senderPlayer -> should say user not registered
        when(perms.hasAdminRights(users.get("alpha"))).thenReturn(true);
        DiscordMessage msg = mockMessage("!baninfo", users.get("alpha"), admChannel);
        bot.recvMessage(msg);

        verify(msg).reply(Config.user_not_registered);
    }

    @Test void baninfo_fromAdminChannel_nonAdmin_ignored() {
        when(perms.hasAdminRights(users.get("delta"))).thenReturn(false);
        when(perms.hasSuperAdminRights(users.get("delta"))).thenReturn(false);
        DiscordMessage msg = mockMessage("!baninfo <@1001>", users.get("delta"), admChannel);
        bot.recvMessage(msg);

        // Non-admin in admin channel - command should not execute, no reply
        verify(msg, never()).reply(anyString());
    }

    @Test void baninfo_fromPublicChannel_unregisteredUser() {
        var unknown = mockUser("9999", "unknown");
        DiscordMessage msg = mockMessage("!baninfo", unknown, pubChannel);
        bot.recvMessage(msg);

        verify(msg).reply(Config.user_not_registered);
    }

    // ========== Helpers ==========

    static Gametype gt(String name) { return logic.getGametypeByString(name); }

    static void assertContains(PickupReply r, String sub) {
        org.junit.jupiter.api.Assertions.assertNotNull(r.getMessage(), "Reply message was null");
        org.junit.jupiter.api.Assertions.assertTrue(
                r.getMessage().toLowerCase().contains(sub.toLowerCase()),
                "Expected '" + sub + "' in: " + r.getMessage());
    }

    /** Send a command through the bot's recvMessage. */
    static void sendCommand(String content, DiscordUser user, DiscordChannel channel) {
        DiscordMessage msg = mockMessage(content, user, channel);
        bot.recvMessage(msg);
    }

    static DiscordMessage mockMessage(String content, DiscordUser user, DiscordChannel channel) {
        DiscordMessage msg = mock(DiscordMessage.class);
        when(msg.getContent()).thenReturn(content);
        when(msg.getUser()).thenReturn(user);
        when(msg.getChannel()).thenReturn(channel);
        return msg;
    }

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

    static DiscordChannel mockChannel(String id, String name, boolean isPrivate, boolean isThread) {
        var ch = mock(DiscordChannel.class);
        when(ch.getId()).thenReturn(id);
        when(ch.getName()).thenReturn(name);
        when(ch.isPrivateChannel()).thenReturn(isPrivate);
        when(ch.isThreadChannel()).thenReturn(isThread);
        return ch;
    }

    static void seed(Connection c) throws Exception {
        var s = c.createStatement();
        s.executeUpdate("CREATE TABLE IF NOT EXISTS player (userid TEXT,urtauth TEXT,elo INTEGER DEFAULT 1000,elochange INTEGER DEFAULT 0,active TEXT,country TEXT,enforce_ac TEXT DEFAULT 'true',coins INTEGER DEFAULT 1000,eloboost INTEGER DEFAULT 0,mapvote INTEGER DEFAULT 0,mapban INTEGER DEFAULT 0,proctf TEXT DEFAULT 'true',PRIMARY KEY(userid,urtauth))");
        s.executeUpdate("CREATE TABLE IF NOT EXISTS gametype (gametype TEXT PRIMARY KEY,teamsize INTEGER,active TEXT,recent_map_exclude INTEGER DEFAULT 2)");
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

        s.executeUpdate("INSERT INTO season VALUES(1,1704067200000,1767225600000)");

        s.executeUpdate("INSERT INTO gametype VALUES('TS',5,'true',2)");
        s.executeUpdate("INSERT INTO gametype VALUES('CTF',5,'true',2)");

        for (var m : new String[]{"ut4_turnpike","ut4_abbey","ut4_casa","ut4_uptown","ut4_algiers"})
            s.executeUpdate("INSERT INTO map VALUES('" + m + "','TS','true',0)");
        for (var m : new String[]{"ut4_riyadh","ut4_sanchez","ut4_tohunga_b8"})
            s.executeUpdate("INSERT INTO map VALUES('" + m + "','CTF','true',0)");

        s.executeUpdate("INSERT INTO server VALUES(1,'192.168.1.1',27960,'rcon1','pw1','true','EU')");
        s.executeUpdate("INSERT INTO server VALUES(2,'192.168.1.2',27960,'rcon2','pw2','true','NA')");

        s.executeUpdate("INSERT INTO channels VALUES('9001','PUBLIC')");
        s.executeUpdate("INSERT INTO channels VALUES('9002','ADMIN')");

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
}
