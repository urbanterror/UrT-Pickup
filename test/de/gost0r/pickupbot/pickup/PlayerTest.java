package de.gost0r.pickupbot.pickup;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.gost0r.pickupbot.discord.DiscordUser;
import de.gost0r.pickupbot.discord.MockDiscordBot;

public class PlayerTest {
    
    private MockDiscordBot mockBot;
    private Database db;
    private PickupLogic logic;
    private final String TEST_CHANNEL = "test-channel";
    private final String TEST_DB_PATH = "test_pickup.db";
    
    @BeforeEach
    public void setUp() throws Exception {
        // Set up test database
        db = new Database(TEST_DB_PATH);
        
        // Set up mock bot
        mockBot = new MockDiscordBot();
        mockBot.setLatestMessageChannel(TEST_CHANNEL);
        
        // Set up logic
        logic = new PickupLogic(mockBot, db);
        mockBot.setLogic(logic);
        
        // Set static references
        Player.db = db;
    }
    
    @AfterEach
    public void tearDown() {
        // Delete test database
        File dbFile = new File(TEST_DB_PATH);
        if (dbFile.exists()) {
            dbFile.delete();
        }
    }
    
    @Test
    public void testCreatePlayer() {
        // Create a new player
        DiscordUser user = new DiscordUser("123456789", "TestUser", "1234");
        Player player = new Player(user, "testplayer");
        player.save();
        
        // Verify player was saved
        Player retrievedPlayer = Player.get(user);
        assertNotNull(retrievedPlayer);
        assertEquals("testplayer", retrievedPlayer.getUrtauth());
        assertEquals(1000, retrievedPlayer.getCoins()); // Default coins
    }
    
    @Test
    public void testUpdatePlayer() {
        // Create a player
        DiscordUser user = new DiscordUser("123456789", "TestUser", "1234");
        Player player = new Player(user, "testplayer");
        player.save();
        
        // Update player
        player.setCoins(1500);
        player.setElo(1200);
        player.save();
        
        // Verify player was updated
        Player retrievedPlayer = Player.get(user);
        assertEquals(1500, retrievedPlayer.getCoins());
        assertEquals(1200, retrievedPlayer.getElo());
    }
    
    @Test
    public void testResetWalletForNewSeason() {
        // Create a player with custom coins
        DiscordUser user = new DiscordUser("123456789", "TestUser", "1234");
        Player player = new Player(user, "testplayer");
        player.setCoins(1500);
        player.save();
        
        // Create a season
        Season season = new Season(1, System.currentTimeMillis(), 0);
        db.saveSeason(season);
        
        // Save wallet history
        db.saveWalletHistory(player, 1, 1500);
        
        // Reset wallet for new season
        player.resetWalletForNewSeason();
        player.save();
        
        // Verify wallet was reset
        Player retrievedPlayer = Player.get(user);
        assertEquals(1000, retrievedPlayer.getCoins());
    }
    
    @Test
    public void testRegisterCommand() {
        // Create a user
        DiscordUser user = new DiscordUser("123456789", "TestUser", "1234");
        
        // Test !register command
        mockBot.simulateCommand("!register testplayer", user);
        
        // Verify response
        List<String> notices = mockBot.getUserNotices(user);
        assertTrue(notices.get(0).contains("successfully registered"));
        
        // Verify player was created
        Player player = Player.get(user);
        assertNotNull(player);
        assertEquals("testplayer", player.getUrtauth());
        assertEquals(1000, player.getCoins());
    }
    
    @Test
    public void testDonateCommand() {
        // Create two players
        DiscordUser user1 = new DiscordUser("123456789", "TestUser1", "1234");
        Player player1 = new Player(user1, "testplayer1");
        player1.setCoins(1500);
        player1.save();
        
        DiscordUser user2 = new DiscordUser("987654321", "TestUser2", "4321");
        Player player2 = new Player(user2, "testplayer2");
        player2.setCoins(1000);
        player2.save();
        
        // Test !donate command
        mockBot.simulateCommand("!donate testplayer2 500", user1);
        
        // Verify response
        List<String> messages = mockBot.getChannelMessages(TEST_CHANNEL);
        assertTrue(messages.get(0).contains("donated 500"));
        
        // Verify coins were transferred
        player1 = Player.get(user1); // Reload players
        player2 = Player.get(user2);
        assertEquals(1000, player1.getCoins());
        assertEquals(1500, player2.getCoins());
    }
    
    @Test
    public void testGetWalletHistory() {
        // Create a player
        DiscordUser user = new DiscordUser("123456789", "TestUser", "1234");
        Player player = new Player(user, "testplayer");
        player.setCoins(1500);
        player.save();
        
        // Create seasons and save wallet history
        Season season1 = new Season(1, System.currentTimeMillis() - 172800000, System.currentTimeMillis() - 86400000);
        db.saveSeason(season1);
        db.saveWalletHistory(player, 1, 1500);
        
        Season season2 = new Season(2, System.currentTimeMillis() - 86400000, 0);
        db.saveSeason(season2);
        db.saveWalletHistory(player, 2, 2000);
        
        // Get wallet history
        List<WalletHistoryEntry> history = player.getWalletHistory();
        
        // Verify history
        assertEquals(2, history.size());
        
        // Find entries for each season
        WalletHistoryEntry season1Entry = history.stream()
                .filter(entry -> entry.getSeasonNumber() == 1)
                .findFirst()
                .orElse(null);
        
        WalletHistoryEntry season2Entry = history.stream()
                .filter(entry -> entry.getSeasonNumber() == 2)
                .findFirst()
                .orElse(null);
        
        assertNotNull(season1Entry);
        assertNotNull(season2Entry);
        assertEquals(1500, season1Entry.getBalance());
        assertEquals(2000, season2Entry.getBalance());
    }
}