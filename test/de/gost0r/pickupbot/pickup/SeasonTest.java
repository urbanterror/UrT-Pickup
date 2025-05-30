package de.gost0r.pickupbot.pickup;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.gost0r.pickupbot.discord.DiscordUser;
import de.gost0r.pickupbot.discord.MockDiscordBot;

public class SeasonTest {
    
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
        Season.db = db;
        
        // Create test user and player
        testUser = new DiscordUser("123456789", "TestUser", "1234");
        testPlayer = new Player(testUser, "testplayer");
        testPlayer.setCoins(1500);
        testPlayer.save();
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
    public void testCreateSeason() {
        // Create a new season
        Season season = new Season(1, System.currentTimeMillis(), 0);
        db.saveSeason(season);
        
        // Verify season was saved
        Season retrievedSeason = db.getCurrentSeason();
        assertNotNull(retrievedSeason);
        assertEquals(1, retrievedSeason.getNumber());
    }
    
    @Test
    public void testEndSeason() {
        // Create a season
        long startTime = System.currentTimeMillis() - 86400000; // 1 day ago
        Season season = new Season(1, startTime, 0);
        db.saveSeason(season);
        
        // End the season
        season.setEndDate(System.currentTimeMillis());
        db.saveSeason(season);
        
        // Verify season was updated
        Season retrievedSeason = db.getSeason(1);
        assertNotNull(retrievedSeason);
        assertTrue(retrievedSeason.getEndDate() > 0);
    }
    
    @Test
    public void testCreateNewSeason() {
        // Create initial season
        Season season1 = new Season(1, System.currentTimeMillis() - 86400000, 0);
        db.saveSeason(season1);
        
        // Save wallet history for player
        db.saveWalletHistory(testPlayer, 1, 1500);
        
        // Create new season
        db.createNewSeason(2);
        
        // Verify new season was created
        Season season2 = db.getSeason(2);
        assertNotNull(season2);
        assertEquals(2, season2.getNumber());
        
        // Verify previous season was ended
        Season season1Updated = db.getSeason(1);
        assertTrue(season1Updated.getEndDate() > 0);
        
        // Verify player wallet was reset
        testPlayer = Player.get(testUser); // Reload player
        assertEquals(1000, testPlayer.getCoins());
        
        // Verify wallet history was saved for both seasons
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
    public void testGetCurrentSeason() {
        // Create multiple seasons
        Season season1 = new Season(1, System.currentTimeMillis() - 172800000, System.currentTimeMillis() - 86400000); // 2 days ago to 1 day ago
        db.saveSeason(season1);
        
        Season season2 = new Season(2, System.currentTimeMillis() - 86400000, 0); // 1 day ago to now
        db.saveSeason(season2);
        
        // Verify current season
        Season currentSeason = db.getCurrentSeason();
        assertNotNull(currentSeason);
        assertEquals(2, currentSeason.getNumber());
    }
    
    @Test
    public void testSeasonCommand() {
        // Create a season
        Season season = new Season(1, System.currentTimeMillis() - 86400000, 0);
        db.saveSeason(season);
        
        // Test !season command
        mockBot.simulateCommand("!season", testUser);
        
        // Verify response
        List<String> messages = mockBot.getChannelMessages(TEST_CHANNEL);
        assertEquals(1, messages.size());
        assertTrue(messages.get(0).contains("Season 1"));
    }
}