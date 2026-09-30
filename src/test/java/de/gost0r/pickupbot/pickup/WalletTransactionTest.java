package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.discord.DiscordInteraction;
import de.gost0r.pickupbot.discord.DiscordService;
import de.gost0r.pickupbot.discord.DiscordUser;
import de.gost0r.pickupbot.ftwgl.FtwglApi;
import de.gost0r.pickupbot.permission.PermissionService;
import de.gost0r.pickupbot.permission.PickupRoleCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WalletTransactionTest {
    @TempDir Path dir;

    enum Perk {
        BOOST(1000, "eloboost"), VOTES(1000, "mapvote"), MAP_BAN(10000, "mapban");

        final int price;
        final String column;

        Perk(int price, String column) {
            this.price = price;
            this.column = column;
        }

        void buy(PickupLogic logic, DiscordInteraction interaction, Player player) {
            switch (this) {
                case BOOST -> logic.buyBoost(interaction, player);
                case VOTES -> logic.buyAdditionalVotes(interaction, player, 1);
                case MAP_BAN -> logic.buyBanMap(interaction, player);
            }
        }

        long cachedValue(Player player) {
            return switch (this) {
                case BOOST -> player.getEloBoost();
                case VOTES -> player.getAdditionalMapVotes();
                case MAP_BAN -> player.getMapBans();
            };
        }
    }

    private final class Fixture implements AutoCloseable {
        final Database previous = Player.db;
        final PickupBot bot;
        final PickupLogic logic;
        final Database db;
        final Connection reader;

        Fixture() throws Exception {
            DiscordService discord = mock(DiscordService.class);
            PermissionService permissions = mock(PermissionService.class);
            PickupRoleCache roles = mock(PickupRoleCache.class);
            FtwglApi ftw = mock(FtwglApi.class);
            String prefix = dir.resolve("wallet").toString();
            bot = spy(new PickupBot(prefix, ftw, discord, permissions, roles,
                    Runnable::run, Runnable::run, Runnable::run, Runnable::run));
            doNothing().when(bot).sendMsg(anyList(), anyString());
            logic = spy(new PickupLogic(bot, ftw, discord, permissions, roles));
            doReturn(List.of()).when(logic).getChannelByType(any());
            db = new Database(logic, discord, permissions);
            logic.db = db;
            Player.db = db;
            reader = DriverManager.getConnection("jdbc:sqlite:" + prefix + ".pickup.db");
        }

        Player player(String id, long balance) {
            DiscordUser user = mock(DiscordUser.class);
            when(user.getId()).thenReturn(id);
            when(user.getMentionString()).thenReturn("<@" + id + ">");
            Player player = Player.detached(user, "wallet-" + id);
            player.setCountry("US");
            db.createPlayer(player);
            db.updatePlayerCoins(player, balance - 1000);
            player.setCoins(balance);
            return player;
        }

        Connection command() throws Exception {
            var field = Database.class.getDeclaredField("c");
            field.setAccessible(true);
            return (Connection) field.get(db);
        }

        long value(String sql) throws Exception {
            try (Statement st = reader.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }

        @Override public void close() throws Exception {
            Player.db = previous;
            reader.close();
            db.disconnect();
        }
    }

    @Test
    void donationSucceedsDespiteAnOlderAutocommitReadOnTheCommandConnection() throws Exception {
        try (Fixture f = new Fixture()) {
            Player sender = f.player("1", 1000), recipient = f.player("2", 1000);
            Connection command = f.command();
            try (Statement st = command.createStatement(); ResultSet read = st.executeQuery("SELECT coins FROM player")) {
                assertTrue(read.next());
                assertTrue(command.getAutoCommit());
                f.db.updatePlayerCoins(sender, 50); // a wallet/bet/settlement commits while a command is reading
                assertTrue(f.db.transferCoins(sender, recipient, 100));
                assertEquals(950, f.value("SELECT coins FROM player WHERE userid='1'"));
                assertEquals(1100, f.value("SELECT coins FROM player WHERE userid='2'"));
            }
            assertTrue(command.getAutoCommit());
            assertEquals(2050, f.value("SELECT sum(coins) FROM player"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void seasonLookupDoesNotLeaveAReadSnapshotBlockingCommandsAfterDonation(boolean current) throws Exception {
        try (Fixture f = new Fixture()) {
            Player sender = f.player("1", 1000), recipient = f.player("2", 1000);
            Connection command = f.command();
            try (Statement st = command.createStatement()) {
                st.execute("INSERT OR REPLACE INTO season (number, startdate, enddate) VALUES (99, 0, 1000)");
            }
            assertNotNull(current ? f.db.getCurrentSeason() : f.db.getSeason(99));
            assertTrue(f.db.transferCoins(sender, recipient, 100));
            try (Statement st = command.createStatement()) {
                assertEquals(1, st.executeUpdate("UPDATE player SET country='DE' WHERE userid='1'"));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Perk.class)
    void purchaseRejectsStaleFundsWithoutGrantingAPerkOrRetainingADebit(Perk perk) throws Exception {
        try (Fixture f = new Fixture()) {
            Player buyer = f.player("1", perk.price), recipient = f.player("2", 1000);
            // A donation can commit before its caller refreshes the shared Player object.
            assertTrue(f.db.transferCoins(buyer, recipient, perk.price - 100));
            assertEquals(perk.price, buyer.getCoins());
            DiscordInteraction interaction = mock(DiscordInteraction.class);

            assertDoesNotThrow(() -> perk.buy(f.logic, interaction, buyer));
            verify(interaction).respondEphemeral(Config.bets_insufficient);
            verify(f.bot, never()).sendMsg(anyList(), anyString());
            assertEquals(0, perk.cachedValue(buyer));
            assertEquals(0, f.value("SELECT " + perk.column + " FROM player WHERE userid='1'"));
            assertEquals(100, f.value("SELECT coins FROM player WHERE userid='1'"));
            buyer.refreshWallet();
            assertEquals(100, buyer.getCoins());
            buyer.addCoins(30);
            buyer.saveWallet();
            assertEquals(130, f.value("SELECT coins FROM player WHERE userid='1'"));
        }
    }

    @ParameterizedTest
    @EnumSource(Perk.class)
    void databaseFailureRollsBackPurchaseAndLeavesNoDebitToRetry(Perk perk) throws Exception {
        try (Fixture f = new Fixture()) {
            Player buyer = f.player("1", perk.price);
            try (Statement st = f.reader.createStatement()) {
                st.execute("CREATE TRIGGER fail_purchase BEFORE UPDATE OF coins ON player "
                        + "WHEN NEW.coins < OLD.coins BEGIN SELECT RAISE(ABORT, 'test purchase failure'); END");
            }
            DiscordInteraction interaction = mock(DiscordInteraction.class);
            assertThrows(MatchPersistenceException.class, () -> perk.buy(f.logic, interaction, buyer));
            verifyNoInteractions(interaction);
            verify(f.bot, never()).sendMsg(anyList(), anyString());
            assertEquals(0, f.value("SELECT " + perk.column + " FROM player WHERE userid='1'"));
            assertEquals(0, perk.cachedValue(buyer));
            assertEquals(perk.price, buyer.getCoins());
            assertEquals(perk.price, f.value("SELECT coins FROM player WHERE userid='1'"));
            try (Statement st = f.reader.createStatement()) { st.execute("DROP TRIGGER fail_purchase"); }
            buyer.addCoins(30);
            buyer.saveWallet();
            assertEquals(perk.price + 30, f.value("SELECT coins FROM player WHERE userid='1'"));
        }
    }

    @ParameterizedTest
    @EnumSource(Perk.class)
    void successfulPurchaseDebitsOnceAndUpdatesTheCachedPerk(Perk perk) throws Exception {
        try (Fixture f = new Fixture()) {
            Player buyer = f.player("1", perk.price + 500);
            DiscordInteraction interaction = mock(DiscordInteraction.class);
            perk.buy(f.logic, interaction, buyer);
            assertEquals(500, buyer.getCoins());
            assertEquals(500, f.value("SELECT coins FROM player WHERE userid='1'"));
            long stored = f.value("SELECT " + perk.column + " FROM player WHERE userid='1'");
            assertTrue(stored > 0);
            assertEquals(stored, perk.cachedValue(buyer));
            buyer.saveWallet();
            assertEquals(500, f.value("SELECT coins FROM player WHERE userid='1'"));
        }
    }

    @ParameterizedTest
    @EnumSource(value = Perk.class, names = {"BOOST", "VOTES"})
    void competingConnectionsCannotChargeTwiceForAnAlreadyOwnedPerk(Perk perk) throws Exception {
        try (Fixture f = new Fixture()) {
            Player buyer = f.player("1", perk.price * 2L);
            Database second = new Database(f.logic, mock(DiscordService.class), mock(PermissionService.class));
            Database.Perk type = perk == Perk.BOOST ? Database.Perk.ELO_BOOST : Database.Perk.MAP_VOTES;
            try (var pool = Executors.newFixedThreadPool(2)) {
                CountDownLatch start = new CountDownLatch(1);
                var firstPurchase = pool.submit(() -> { start.await(); return f.db.purchasePerk(buyer, type, 1); });
                var secondPurchase = pool.submit(() -> { start.await(); return second.purchasePerk(buyer, type, 1); });
                start.countDown();
                var statuses = List.of(firstPurchase.get(5, TimeUnit.SECONDS).status(),
                        secondPurchase.get(5, TimeUnit.SECONDS).status());
                assertTrue(statuses.contains(Database.PurchaseStatus.PURCHASED));
                assertTrue(statuses.contains(Database.PurchaseStatus.ALREADY_OWNED));
                assertEquals(perk.price, f.value("SELECT coins FROM player WHERE userid='1'"));
                assertTrue(f.value("SELECT " + perk.column + " FROM player WHERE userid='1'") > 0);
            } finally { second.disconnect(); }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4, 5})
    void additionalVoteBundlesUseTheirFullPrice(int number) throws Exception {
        try (Fixture f = new Fixture()) {
            int price = 1000 << (number - 1);
            Player buyer = f.player("1", price + 500);
            f.logic.buyAdditionalVotes(mock(DiscordInteraction.class), buyer, number);
            assertEquals(500, buyer.getCoins());
            assertEquals(500, f.value("SELECT coins FROM player WHERE userid='1'"));
            assertEquals(number, buyer.getAdditionalMapVotes());
            assertEquals(number, f.value("SELECT mapvote FROM player WHERE userid='1'"));
        }
    }

    @Test
    void purchasePreservesAnUnrelatedPendingCreditAndMapBansCanBeBoughtAgain() throws Exception {
        try (Fixture f = new Fixture()) {
            Player buyer = f.player("1", 20500);
            buyer.addCoins(30);
            f.logic.buyBanMap(mock(DiscordInteraction.class), buyer);
            f.logic.buyBanMap(mock(DiscordInteraction.class), buyer);
            assertEquals(2, buyer.getMapBans());
            assertEquals(2, f.value("SELECT mapban FROM player WHERE userid='1'"));
            assertEquals(530, buyer.getCoins());
            assertEquals(500, f.value("SELECT coins FROM player WHERE userid='1'"));
            buyer.saveWallet();
            assertEquals(530, f.value("SELECT coins FROM player WHERE userid='1'"));
        }
    }
}
