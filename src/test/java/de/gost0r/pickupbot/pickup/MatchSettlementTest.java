package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.discord.DiscordService;
import de.gost0r.pickupbot.discord.DiscordUser;
import de.gost0r.pickupbot.ftwgl.FtwglApi;
import de.gost0r.pickupbot.permission.PermissionService;
import de.gost0r.pickupbot.permission.PickupRoleCache;
import de.gost0r.pickupbot.pickup.server.Server;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MatchSettlementTest {
    @TempDir Path dir;

    private Database open(String prefix) {
        DiscordService discord = mock(DiscordService.class);
        PermissionService permissions = mock(PermissionService.class);
        FtwglApi ftw = mock(FtwglApi.class);
        PickupBot bot = new PickupBot(prefix, ftw, discord, permissions, mock(PickupRoleCache.class),
                Runnable::run, Runnable::run, Runnable::run, Runnable::run);
        return new Database(new PickupLogic(bot, ftw, discord, permissions, mock(PickupRoleCache.class)), discord, permissions);
    }

    private static Player player(String id) {
        DiscordUser user = mock(DiscordUser.class);
        when(user.getId()).thenReturn(id);
        Player p = mock(Player.class);
        when(p.getDiscordUser()).thenReturn(user);
        when(p.getUrtauth()).thenReturn("auth-" + id);
        when(p.getCountry()).thenReturn("US");
        return p;
    }

    private static Match match(Player red, Player blue) {
        Match m = mock(Match.class);
        when(m.getGametype()).thenReturn(new Gametype("TS", 1, true, false));
        Server server = mock(Server.class);
        server.id = 1;
        when(m.getServer()).thenReturn(server);
        when(m.getMap()).thenReturn(new GameMap("ut4_turnpike"));
        when(m.getPlayerList()).thenReturn(List.of(red, blue));
        when(m.getTeam(red)).thenReturn("red");
        when(m.getTeam(blue)).thenReturn("blue");
        when(m.getStats(red)).thenReturn(new MatchStats());
        when(m.getStats(blue)).thenReturn(new MatchStats());
        when(m.getMatchState()).thenReturn(MatchState.Live);
        return m;
    }

    private static long value(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    @Test
    void duplicateSpreeRowsUseLatestValueAndUpdateEveryRowForExactIdentityOnce() throws Exception {
        String prefix = dir.resolve("duplicate-spree").toString();
        Database db = open(prefix);
        Player red = player("1"), blue = player("2");
        Player otherAuth = mock(Player.class);
        DiscordUser redUser = red.getDiscordUser();
        when(otherAuth.getDiscordUser()).thenReturn(redUser);
        when(otherAuth.getUrtauth()).thenReturn("auth-other");
        when(otherAuth.getCountry()).thenReturn("US");
        try (Connection reader = DriverManager.getConnection("jdbc:sqlite:" + prefix + ".pickup.db")) {
            for (Player p : List.of(red, blue, otherAuth)) db.createPlayer(p);
            db.updateGametype(new Gametype("TS", 1, true, false));
            try (Statement st = reader.createStatement()) {
                for (int i = 0; i < 2; i++) {
                    st.executeUpdate("INSERT INTO spree (player_userid, player_urtauth, gametype, spree, personal_best) "
                            + "VALUES ('1', 'auth-1', 'TS', 4, 4)");
                }
                st.executeUpdate("INSERT INTO spree (player_userid, player_urtauth, gametype, spree, personal_best) "
                        + "VALUES ('1', 'auth-other', 'TS', 7, 7)");
            }
            Match first = match(red, blue);
            int id = db.createMatch(first);
            when(first.getID()).thenReturn(id);
            when(first.getMatchState()).thenReturn(MatchState.Done);
            when(first.getScoreRed()).thenReturn(3);
            db.saveMatch(first);
            db.settleMatch(id);
            db.settleMatch(id);
            assertEquals(2, value(reader, "SELECT count(*) FROM spree WHERE player_userid='1' AND player_urtauth='auth-1' AND spree=5 AND personal_best=5"));
            assertEquals(7, value(reader, "SELECT spree FROM spree WHERE player_userid='1' AND player_urtauth='auth-other'"));
            assertEquals(1050, value(reader, "SELECT coins FROM player WHERE userid='1' AND urtauth='auth-1'"));

            // If legacy duplicates disagree, the newest row is authoritative.
            try (Statement st = reader.createStatement()) {
                st.executeUpdate("UPDATE spree SET spree=1 WHERE ID=(SELECT MIN(ID) FROM spree "
                        + "WHERE player_userid='1' AND player_urtauth='auth-1' AND gametype='TS')");
            }
            Match second = match(red, blue);
            int nextId = db.createMatch(second);
            when(second.getID()).thenReturn(nextId);
            when(second.getMatchState()).thenReturn(MatchState.Done);
            when(second.getScoreRed()).thenReturn(3);
            db.saveMatch(second);
            db.settleMatch(nextId);
            db.settleMatch(nextId);
            assertEquals(2, value(reader, "SELECT count(*) FROM spree WHERE player_userid='1' AND player_urtauth='auth-1' AND spree=6 AND personal_best=6"));
            assertEquals(7, value(reader, "SELECT spree FROM spree WHERE player_userid='1' AND player_urtauth='auth-other'"));
            assertEquals(1100, value(reader, "SELECT coins FROM player WHERE userid='1' AND urtauth='auth-1'"));
            assertEquals(1000, value(reader, "SELECT coins FROM player WHERE userid='1' AND urtauth='auth-other'"));
        } finally { db.disconnect(); }
    }

    @Test
    void privateGametypeWithoutCatalogRowStillPaysParticipantsStreaksAndBetsAfterRestart() throws Exception {
        String prefix = dir.resolve("private").toString();
        Database db = open(prefix);
        Player captain = player("1"), teammate = player("2"), blue = player("3"), opponent = player("4"), bettor = player("5");
        try (Connection reader = DriverManager.getConnection("jdbc:sqlite:" + prefix + ".pickup.db")) {
            for (Player p : List.of(captain, teammate, blue, opponent, bettor)) db.createPlayer(p);
            db.updateGametype(new Gametype("TS", 2, true, false));
            PrivateGroup group = new PrivateGroup(captain, new Gametype("TS", 2, true, false));
            Match m = match(captain, blue);
            when(m.getGametype()).thenReturn(group.gt);
            when(m.getPlayerList()).thenReturn(List.of(captain, teammate, blue, opponent));
            when(m.getTeam(teammate)).thenReturn("red");
            when(m.getTeam(opponent)).thenReturn("blue");
            when(m.getStats(teammate)).thenReturn(new MatchStats());
            when(m.getStats(opponent)).thenReturn(new MatchStats());
            int id = db.createMatch(m);
            when(m.getID()).thenReturn(id);
            assertEquals(0, value(reader, "SELECT count(*) FROM gametype WHERE gametype='TS auth-1'"));
            assertEquals(2, value(reader, "SELECT teamsize FROM match_settlement WHERE matchid=" + id));
            assertTrue(db.placeBet(new Bet(id, bettor, "red", 100, 2), false));
            when(m.getMatchState()).thenReturn(MatchState.Done);
            when(m.getScoreRed()).thenReturn(4);
            when(m.getScoreBlue()).thenReturn(2);
            db.saveMatch(m);
            db.disconnect();
            db = open(prefix);
            db.recoverSettlements();
            assertEquals(1050, value(reader, "SELECT coins FROM player WHERE userid='1'"));
            assertEquals(1050, value(reader, "SELECT coins FROM player WHERE userid='2'"));
            assertEquals(1025, value(reader, "SELECT coins FROM player WHERE userid='3'"));
            assertEquals(1025, value(reader, "SELECT coins FROM player WHERE userid='4'"));
            assertEquals(1100, value(reader, "SELECT coins FROM player WHERE userid='5'"));
            assertEquals(4, value(reader, "SELECT count(*) FROM spree WHERE gametype='TS auth-1'"));
            assertEquals(2, value(reader, "SELECT count(*) FROM spree WHERE gametype='TS auth-1' AND spree=-1"));
            assertEquals(1, value(reader, "SELECT count(*) FROM bets WHERE matchid=" + id + " AND won='true' AND open=0"));
            db.settleMatch(id);
            assertEquals(1100, value(reader, "SELECT coins FROM player WHERE userid='5'"));
        } finally { db.disconnect(); }
    }

    @Test
    void oldMarkerSchemaBackfillsKnownModeButRefusesUnknownModeRatherThanRefunding() throws Exception {
        String prefix = dir.resolve("migration").toString();
        Database db = open(prefix);
        Player red = player("1"), blue = player("2");
        try (Connection reader = DriverManager.getConnection("jdbc:sqlite:" + prefix + ".pickup.db")) {
            db.createPlayer(red);
            db.createPlayer(blue);
            db.updateGametype(new Gametype("TS", 1, true, false));
            int knownId = db.createMatch(match(red, blue));
            Match unknown = match(red, blue);
            Gametype privateMode = new PrivateGroup(red, new Gametype("TS", 1, true, false)).gt;
            when(unknown.getGametype()).thenReturn(privateMode);
            int unknownId = db.createMatch(unknown);
            try (Statement st = reader.createStatement()) {
                st.execute("UPDATE match SET state='Done', score_red=3, score_blue=1 WHERE ID IN (" + knownId + "," + unknownId + ")");
            }
            db.disconnect();
            try (Statement st = reader.createStatement()) {
                st.execute("ALTER TABLE match_settlement DROP COLUMN teamsize"); // previous deployment's marker format
            }
            db = open(prefix);
            assertEquals(1, value(reader, "SELECT teamsize FROM match_settlement WHERE matchid=" + knownId));
            assertEquals(1, value(reader, "SELECT teamsize IS NULL FROM match_settlement WHERE matchid=" + unknownId));
            Database migrated = db;
            assertThrows(MatchPersistenceException.class, () -> migrated.settleMatch(unknownId));
            assertEquals(0, value(reader, "SELECT settled FROM match_settlement WHERE matchid=" + unknownId));
            db.settleMatch(knownId);
            assertEquals(1050, value(reader, "SELECT coins FROM player WHERE userid='1'"));
        } finally { db.disconnect(); }
    }

    @Test
    void livePrivateMatchCanReloadWithItsStoredGametypeAfterRestart() throws Exception {
        String prefix = dir.resolve("live-private").toString();
        DiscordService discord = mock(DiscordService.class);
        PermissionService permissions = mock(PermissionService.class);
        FtwglApi ftw = mock(FtwglApi.class);
        PickupBot bot = new PickupBot(prefix, ftw, discord, permissions, mock(PickupRoleCache.class),
                Runnable::run, Runnable::run, Runnable::run, Runnable::run);
        PickupLogic logic = mock(PickupLogic.class);
        logic.bot = bot;
        Server server = mock(Server.class);
        server.id = 1;
        when(logic.getServerByID(1)).thenReturn(server);
        when(logic.getMapByName("ut4_turnpike")).thenReturn(new GameMap("ut4_turnpike"));
        Database db = new Database(logic, discord, permissions);
        try {
            Match m = mock(Match.class);
            Gametype privateMode = new PrivateGroup(player("1"), new Gametype("TS", 2, true, false)).gt;
            when(m.getGametype()).thenReturn(privateMode);
            when(m.getServer()).thenReturn(server);
            when(m.getMap()).thenReturn(new GameMap("ut4_turnpike"));
            when(m.getMatchState()).thenReturn(MatchState.Live);
            when(m.getPlayerList()).thenReturn(List.of());
            int id = db.createMatch(m);
            db.disconnect();
            db = new Database(logic, discord, permissions);
            Match loaded = db.loadMatch(id);
            assertNotNull(loaded);
            assertEquals("TS auth-1", loaded.getGametype().getName());
            assertEquals(2, loaded.getGametype().getTeamSize());
            assertTrue(loaded.getGametype().getPrivate());
        } finally { db.disconnect(); }
    }

    @Test
    void settledExactlyOnceAcrossRestartWithMultipleBetsAndOtherWalletWrites() throws Exception {
        String prefix = dir.resolve("restart").toString();
        Database db = open(prefix);
        Player red = player("1"), blue = player("2"), bettor = player("3");
        try (Connection reader = DriverManager.getConnection("jdbc:sqlite:" + prefix + ".pickup.db")) {
            for (Player p : List.of(red, blue, bettor)) db.createPlayer(p);
            db.updateGametype(new Gametype("TS", 1, true, false));
            Match m = match(red, blue);
            int id = db.createMatch(m);
            when(m.getID()).thenReturn(id);
            assertTrue(db.placeBet(new Bet(id, bettor, "red", 100, 2), false));
            assertTrue(db.placeBet(new Bet(id, bettor, "red", 50, 9), false));
            assertTrue(db.placeBet(new Bet(id, bettor, "blue", 200, 2), false));
            assertEquals(650, value(reader, "SELECT coins FROM player WHERE userid='3'"));
            assertEquals(2, value(reader, "SELECT count(*) FROM bets WHERE open=1"));
            assertFalse(db.placeBet(new Bet(id, bettor, "red", 700, 2), false));
            db.updatePlayerCoins(bettor, 17); // concurrent-style unrelated wallet adjustment
            when(m.getMatchState()).thenReturn(MatchState.Done);
            when(m.getScoreRed()).thenReturn(3);
            when(m.getScoreBlue()).thenReturn(1);
            db.saveMatch(m);
            db.disconnect(); // result committed; process died before its callback
            db = open(prefix);
            db.recoverSettlements();
            assertEquals(967, value(reader, "SELECT coins FROM player WHERE userid='3'")); // 1000-350+17+300
            assertEquals(1050, value(reader, "SELECT coins FROM player WHERE userid='1'"));
            assertEquals(1025, value(reader, "SELECT coins FROM player WHERE userid='2'"));
            assertEquals(1, value(reader, "SELECT spree FROM spree WHERE player_userid='1'"));
            assertEquals(-1, value(reader, "SELECT spree FROM spree WHERE player_userid='2'"));
            assertEquals(2, value(reader, "SELECT count(*) FROM bets WHERE open=0"));
            db.settleMatch(id);
            db.recoverSettlements();
            assertEquals(967, value(reader, "SELECT coins FROM player WHERE userid='3'"));
            assertEquals(2, value(reader, "SELECT count(*) FROM bets"));
        } finally { db.disconnect(); }
    }

    @Test
    void failureHalfwayRollsBackAllEffectsAndCanRetry() throws Exception {
        String prefix = dir.resolve("rollback").toString();
        Database db = open(prefix);
        Player red = player("1"), blue = player("2"), bettor = player("3");
        try (Connection reader = DriverManager.getConnection("jdbc:sqlite:" + prefix + ".pickup.db")) {
            for (Player p : List.of(red, blue, bettor)) db.createPlayer(p);
            db.updateGametype(new Gametype("TS", 1, true, false));
            Match m = match(red, blue);
            int id = db.createMatch(m);
            when(m.getID()).thenReturn(id);
            assertTrue(db.placeBet(new Bet(id, bettor, "red", 100, 2), false));
            when(m.getMatchState()).thenReturn(MatchState.Done);
            when(m.getScoreRed()).thenReturn(3);
            db.saveMatch(m);
            try (Statement st = reader.createStatement()) {
                st.execute("CREATE TRIGGER fail_credit BEFORE UPDATE OF coins ON player WHEN NEW.userid='3' "
                        + "BEGIN SELECT RAISE(ABORT, 'test settlement failure'); END");
            }
            assertThrows(MatchPersistenceException.class, () -> db.settleMatch(id));
            assertEquals(1000, value(reader, "SELECT coins FROM player WHERE userid='1'"));
            assertEquals(900, value(reader, "SELECT coins FROM player WHERE userid='3'"));
            assertEquals(0, value(reader, "SELECT count(*) FROM spree"));
            assertEquals(1, value(reader, "SELECT count(*) FROM bets WHERE open=1"));
            assertEquals(0, value(reader, "SELECT settled FROM match_settlement WHERE matchid=" + id));
            try (Statement st = reader.createStatement()) { st.execute("DROP TRIGGER fail_credit"); }
            db.settleMatch(id);
            assertEquals(1100, value(reader, "SELECT coins FROM player WHERE userid='3'"));
        } finally { db.disconnect(); }
    }

    @Test
    void drawAndAbortRefundOnceAndConcurrentSettlementAndWalletChangeSerialize() throws Exception {
        String prefix = dir.resolve("draw").toString();
        Database db = open(prefix);
        Player red = player("1"), blue = player("2"), bettor = player("3");
        try (Connection reader = DriverManager.getConnection("jdbc:sqlite:" + prefix + ".pickup.db")) {
            for (Player p : List.of(red, blue, bettor)) db.createPlayer(p);
            db.updateGametype(new Gametype("TS", 1, true, false));
            for (MatchState state : List.of(MatchState.Done, MatchState.Abort, MatchState.Surrender)) {
                Match m = match(red, blue);
                int id = db.createMatch(m);
                when(m.getID()).thenReturn(id);
                assertTrue(db.placeBet(new Bet(id, bettor, "red", 100, 2), false));
                when(m.getMatchState()).thenReturn(state);
                db.saveMatch(m);
                db.settleMatch(id);
                db.settleMatch(id);
            }
            assertEquals(1000, value(reader, "SELECT coins FROM player WHERE userid='3'"));
            assertEquals(0, value(reader, "SELECT count(*) FROM bets"));
            assertEquals(0, value(reader, "SELECT count(*) FROM spree"));

            Match m = match(red, blue);
            int id = db.createMatch(m);
            when(m.getID()).thenReturn(id);
            when(m.getMatchState()).thenReturn(MatchState.Done);
            when(m.getScoreRed()).thenReturn(5);
            db.saveMatch(m);
            Database database = db;
            try (var pool = Executors.newFixedThreadPool(2)) {
                var settlement = pool.submit(() -> database.settleMatch(id));
                var adjustment = pool.submit(() -> database.updatePlayerCoins(red, 200));
                settlement.get(); adjustment.get();
            }
            assertEquals(1250, value(reader, "SELECT coins FROM player WHERE userid='1'"));
            assertEquals(1, value(reader, "SELECT spree FROM spree WHERE player_userid='1'"));
        } finally { db.disconnect(); }
    }

    @Test
    void competingBetsCannotBothSpendTheSameCoins() throws Exception {
        String prefix = dir.resolve("concurrent-bets").toString();
        Database first = open(prefix), second = open(prefix);
        Player red = player("1"), blue = player("2"), bettor = player("3");
        try (Connection reader = DriverManager.getConnection("jdbc:sqlite:" + prefix + ".pickup.db")) {
            for (Player p : List.of(red, blue, bettor)) first.createPlayer(p);
            first.updateGametype(new Gametype("TS", 1, true, false));
            int id = first.createMatch(match(red, blue));
            try (var pool = Executors.newFixedThreadPool(2)) {
                var a = pool.submit(() -> first.placeBet(new Bet(id, bettor, "red", 700, 2), false));
                var b = pool.submit(() -> second.placeBet(new Bet(id, bettor, "blue", 700, 2), false));
                assertNotEquals(a.get(), b.get());
            }
            assertEquals(300, value(reader, "SELECT coins FROM player WHERE userid='3'"));
            assertEquals(1, value(reader, "SELECT count(*) FROM bets WHERE open=1"));
        } finally { second.disconnect(); first.disconnect(); }
    }

    @Test
    void repeatedAllInRejectsOverflowBeforeEscrowingRefilledWallet() throws Exception {
        String prefix = dir.resolve("all-in-overflow").toString();
        Database db = open(prefix);
        Player red = player("1"), blue = player("2"), bettor = player("3");
        try (Connection reader = DriverManager.getConnection("jdbc:sqlite:" + prefix + ".pickup.db")) {
            for (Player p : List.of(red, blue, bettor)) db.createPlayer(p);
            db.updateGametype(new Gametype("TS", 1, true, false));
            int id = db.createMatch(match(red, blue));
            long firstStake = Long.MAX_VALUE - 50;
            db.updatePlayerCoins(bettor, firstStake - 1000);
            Bet original = new Bet(id, bettor, "red", -1, 0.5f);
            assertTrue(db.placeBet(original, true));
            assertEquals(firstStake, original.amount);
            assertEquals(0, value(reader, "SELECT coins FROM player WHERE userid='3'"));

            db.updatePlayerCoins(bettor, 100); // a separate wallet action refills the bettor
            Bet repeated = new Bet(id, bettor, "red", -1, 0.5f);
            assertFalse(db.placeBet(repeated, true));
            assertEquals(100, value(reader, "SELECT coins FROM player WHERE userid='3'"));
            assertEquals(firstStake, value(reader, "SELECT amount FROM bets WHERE matchid=" + id));
            assertEquals(1, value(reader, "SELECT typeof(amount)='integer' FROM bets WHERE matchid=" + id));
            assertEquals(1, value(reader, "SELECT count(*) FROM bets WHERE matchid=" + id));
            assertEquals(0, value(reader, "SELECT settled FROM match_settlement WHERE matchid=" + id));

            db.updatePlayerCoins(bettor, 1_000_000);
            assertFalse(db.placeBet(new Bet(id, bettor, "blue", 1_000_001, 0.5f), false));
            assertEquals(1_000_100, value(reader, "SELECT coins FROM player WHERE userid='3'"));
            assertEquals(1, value(reader, "SELECT count(*) FROM bets WHERE matchid=" + id));
        } finally { db.disconnect(); }
    }

    @Test
    void failedWalletSaveKeepsDeltaAndSettlementDoesNotOverwriteOtherCredits() throws Exception {
        String prefix = dir.resolve("wallet").toString();
        Database db = open(prefix);
        DiscordUser user = mock(DiscordUser.class);
        when(user.getId()).thenReturn("7");
        Player p = Player.detached(user, "wallet-owner");
        Database previous = Player.db;
        Player.db = db;
        try (Connection reader = DriverManager.getConnection("jdbc:sqlite:" + prefix + ".pickup.db")) {
            db.createPlayer(p);
            p.addCoins(30);
            try (Statement st = reader.createStatement()) {
                st.execute("CREATE TRIGGER fail_wallet BEFORE UPDATE OF coins ON player "
                        + "BEGIN SELECT RAISE(ABORT, 'test wallet failure'); END");
            }
            assertThrows(MatchPersistenceException.class, p::saveWallet);
            assertEquals(1000, value(reader, "SELECT coins FROM player WHERE userid='7'"));
            try (Statement st = reader.createStatement()) { st.execute("DROP TRIGGER fail_wallet"); }
            db.updatePlayerCoins(p, 11);
            p.saveWallet();
            assertEquals(1041, p.getCoins());
            assertEquals(1041, value(reader, "SELECT coins FROM player WHERE userid='7'"));
        } finally { Player.db = previous; db.disconnect(); }
    }

    @Test
    void transferCommitsBothWalletsOrNeitherWithoutUsingCommandTransaction() throws Exception {
        String prefix = dir.resolve("transfer").toString();
        Database db = open(prefix);
        Player sender = player("1"), recipient = player("2");
        try (Connection reader = DriverManager.getConnection("jdbc:sqlite:" + prefix + ".pickup.db")) {
            db.createPlayer(sender);
            db.createPlayer(recipient);
            Field connectionField = Database.class.getDeclaredField("c");
            connectionField.setAccessible(true);
            Connection command = (Connection) connectionField.get(db);

            assertFalse(db.transferCoins(sender, recipient, 1001));
            assertEquals(1000, value(reader, "SELECT coins FROM player WHERE userid='1'"));
            assertEquals(1000, value(reader, "SELECT coins FROM player WHERE userid='2'"));
            assertTrue(command.getAutoCommit());

            assertTrue(db.transferCoins(sender, recipient, 150));
            assertEquals(850, value(reader, "SELECT coins FROM player WHERE userid='1'"));
            assertEquals(1150, value(reader, "SELECT coins FROM player WHERE userid='2'"));
            assertTrue(command.getAutoCommit());

            try (Statement stmt = reader.createStatement()) {
                stmt.execute("CREATE TRIGGER reject_recipient_credit BEFORE UPDATE OF coins ON player "
                        + "WHEN NEW.userid='2' BEGIN SELECT RAISE(ABORT, 'test recipient failure'); END");
            }
            assertThrows(MatchPersistenceException.class, () -> db.transferCoins(sender, recipient, 200));
            assertEquals(850, value(reader, "SELECT coins FROM player WHERE userid='1'"));
            assertEquals(1150, value(reader, "SELECT coins FROM player WHERE userid='2'"));
            assertTrue(command.getAutoCommit());

            try (Statement stmt = reader.createStatement()) { stmt.execute("DROP TRIGGER reject_recipient_credit"); }
            assertTrue(db.transferCoins(sender, recipient, 200));
            assertEquals(650, value(reader, "SELECT coins FROM player WHERE userid='1'"));
            assertEquals(1350, value(reader, "SELECT coins FROM player WHERE userid='2'"));
            assertTrue(command.getAutoCommit());

            // A transfer must not join an unrelated command transaction.
            command.setAutoCommit(false);
            try {
                assertEquals(650, value(command, "SELECT coins FROM player WHERE userid='1'"));
                assertThrows(MatchPersistenceException.class, () -> db.transferCoins(sender, recipient, 50));
                command.rollback();
            } finally {
                command.setAutoCommit(true);
            }
            assertEquals(650, value(reader, "SELECT coins FROM player WHERE userid='1'"));
            assertTrue(db.transferCoins(sender, recipient, 50));
            assertEquals(600, value(reader, "SELECT coins FROM player WHERE userid='1'"));
            assertEquals(1400, value(reader, "SELECT coins FROM player WHERE userid='2'"));
        } finally { db.disconnect(); }
    }

    @Test
    void simultaneousDonationsCannotSpendTheSameCoinsTwice() throws Exception {
        String prefix = dir.resolve("concurrent-transfers").toString();
        Database first = open(prefix), second = open(prefix);
        Player sender = player("1"), recipientA = player("2"), recipientB = player("3");
        try (Connection reader = DriverManager.getConnection("jdbc:sqlite:" + prefix + ".pickup.db")) {
            for (Player p : List.of(sender, recipientA, recipientB)) first.createPlayer(p);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var a = pool.submit(() -> first.transferCoins(sender, recipientA, 700));
                var b = pool.submit(() -> second.transferCoins(sender, recipientB, 700));
                assertNotEquals(a.get(), b.get());
            }
            assertEquals(300, value(reader, "SELECT coins FROM player WHERE userid='1'"));
            assertEquals(3000, value(reader, "SELECT sum(coins) FROM player"));
        } finally { second.disconnect(); first.disconnect(); }
    }
}
