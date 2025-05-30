package de.gost0r.pickupbot.pickup;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.gost0r.pickupbot.discord.DiscordUser;
import de.gost0r.pickupbot.discord.MockDiscordBot;

public class WalletTest {
    
    private MockDiscordBot mockBot;
    private Database db;
    private PickupLogic logic;
    private Player testPlayer;
    private DiscordUser testUser;
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
        
        // Create test user and player
        testUser = new DiscordUser("123456789", "TestUser", "1234");
        testPlayer = new Player(testUser, "testplayer");
        testPlayer.save();
        
        // Create a test season
        Season currentSeason = new Season(1, System.currentTimeMillis(), 0);
        db.saveSeason(currentSeason);
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
    public void testWalletCommand() {
        // Set initial coins
        testPlayer.setCoins(1500);
        testPlayer.save();
        
        // Test !wallet command
        mockBot.simulateCommand("!wallet", testUser);
        
        // Verify response
        List<String> messages = mockBot.getChannelMessages(TEST_CHANNEL);
        assertEquals(1, messages.size());
        assertTrue(messages.get(0).contains("1,500"));
        assertTrue(messages.get(0).contains(testPlayer.getUrtauth()));
    }
    
    @Test
    public void testWalletHistoryCommand() {
        // Set initial coins and save wallet history
        testPlayer.setCoins(1500);
        testPlayer.save();
        
        // Save wallet history for season 1
        db.saveWalletHistory(testPlayer, 1, 1500);
        
        // Create a new season and save wallet history
        Season season2 = new Season(2, System.currentTimeMillis() + 86400000, 0);
        db.saveSeason(season2);
        db.saveWalletHistory(testPlayer, 2, 2000);
        
        // Test !wallethistory command
        mockBot.simulateCommand("!wallethistory", testUser);
        
        // Verify response
        List<String> messages = mockBot.getChannelMessages(TEST_CHANNEL);
        assertEquals(1, messages.size());
        assertTrue(messages.get(0).contains("Season 1"));
        assertTrue(messages.get(0).contains("1,500"));
        assertTrue(messages.get(0).contains("Season 2"));
        assertTrue(messages.get(0).contains("2,000"));
    }
    
    @Test
    public void testWalletResetForNewSeason() {
        // Set initial coins
        testPlayer.setCoins(1500);
        testPlayer.save();
        
        // Save wallet history for season 1
        db.saveWalletHistory(testPlayer, 1, 1500);
        
        // Create a new season
        Season season2 = new Season(2, System.currentTimeMillis() + 86400000, 0);
        db.saveSeason(season2);
        
        // Reset wallet for new season
        db.resetAllPlayerWalletsForNewSeason(2);
        
        // Reload player
        testPlayer = Player.get(testUser);
        
        // Verify wallet was reset to default value
        assertEquals(1000, testPlayer.getCoins());
        
        // Verify wallet history was saved
        List<WalletHistoryEntry> history = testPlayer.getWalletHistory();
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
        assertEquals(1000, season2Entry.getBalance());
    }
    
    @Test
    public void testWalletHistoryOfOtherPlayer() {
        // Create another test user and player
        DiscordUser otherUser = new DiscordUser("987654321", "OtherUser", "4321");
        Player otherPlayer = new Player(otherUser, "otherplayer");
        otherPlayer.save();
        
        // Set coins and save wallet history
        otherPlayer.setCoins(2500);
        otherPlayer.save();
        db.saveWalletHistory(otherPlayer, 1, 2500);
        
        // Test !wallethistory command for other player
        mockBot.simulateCommand("!wallethistory otherplayer", testUser);
        
        // Verify response
        List<String> messages = mockBot.getChannelMessages(TEST_CHANNEL);
        assertEquals(1, messages.size());
        assertTrue(messages.get(0).contains("otherplayer"));
        assertTrue(messages.get(0).contains("Season 1"));
        assertTrue(messages.get(0).contains("2,500"));
    }
}