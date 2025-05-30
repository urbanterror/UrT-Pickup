package de.gost0r.pickupbot.pickup;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.gost0r.pickupbot.discord.DiscordUser;
import de.gost0r.pickupbot.discord.MockDiscordBot;

public class BetTest {
    
    private MockDiscordBot mockBot;
    private Database db;
    private PickupLogic logic;
    private Player testPlayer;
    private DiscordUser testUser;
    private Match testMatch;
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
        Bet.db = db;
        
        // Create test user and player
        testUser = new DiscordUser("123456789", "TestUser", "1234");
        testPlayer = new Player(testUser, "testplayer");
        testPlayer.setCoins(1000);
        testPlayer.save();
        
        // Create a test season
        Season currentSeason = new Season(1, System.currentTimeMillis(), 0);
        db.saveSeason(currentSeason);
        
        // Create a test match
        Gametype gametype = new Gametype("ctf", 2, 5);
        testMatch = new Match(1, gametype);
        testMatch.save();
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
    public void testPlaceBet() {
        // Test placing a bet
        mockBot.simulateCommand("!bet " + testMatch.getID() + " red 100", testUser);
        
        // Verify response
        List<String> messages = mockBot.getChannelMessages(TEST_CHANNEL);
        assertTrue(messages.get(0).contains("placed a bet"));
        assertTrue(messages.get(0).contains("100"));
        
        // Verify player coins were deducted
        testPlayer = Player.get(testUser); // Reload player
        assertEquals(900, testPlayer.getCoins());
        
        // Verify bet was saved
        List<Bet> bets = db.getBetsForMatch(testMatch);
        assertEquals(1, bets.size());
        assertEquals(testPlayer.getID(), bets.get(0).getPlayerID());
        assertEquals(100, bets.get(0).getAmount());
        assertEquals("red", bets.get(0).getTeam());
    }
    
    @Test
    public void testPlaceBetInvalidAmount() {
        // Test placing a bet with invalid amount
        mockBot.simulateCommand("!bet " + testMatch.getID() + " red 2000", testUser);
        
        // Verify error response
        List<String> notices = mockBot.getUserNotices(testUser);
        assertTrue(notices.get(0).contains("don't have enough"));
        
        // Verify player coins were not deducted
        testPlayer = Player.get(testUser); // Reload player
        assertEquals(1000, testPlayer.getCoins());
        
        // Verify no bet was saved
        List<Bet> bets = db.getBetsForMatch(testMatch);
        assertEquals(0, bets.size());
    }
    
    @Test
    public void testPlaceBetInvalidTeam() {
        // Test placing a bet with invalid team
        mockBot.simulateCommand("!bet " + testMatch.getID() + " invalid 100", testUser);
        
        // Verify error response
        List<String> notices = mockBot.getUserNotices(testUser);
        assertTrue(notices.get(0).contains("Invalid team"));
        
        // Verify player coins were not deducted
        testPlayer = Player.get(testUser); // Reload player
        assertEquals(1000, testPlayer.getCoins());
        
        // Verify no bet was saved
        List<Bet> bets = db.getBetsForMatch(testMatch);
        assertEquals(0, bets.size());
    }
    
    @Test
    public void testWinBet() {
        // Place a bet
        Bet bet = new Bet(testMatch.getID(), testPlayer.getID(), "red", 100);
        bet.save();
        
        // Deduct coins from player
        testPlayer.setCoins(900);
        testPlayer.save();
        
        // Set match result (red team wins)
        testMatch.setRedScore(5);
        testMatch.setBlueScore(2);
        testMatch.setState(MatchState.FINISHED);
        testMatch.save();
        
        // Process bet results
        db.processBetsForMatch(testMatch);
        
        // Verify player received winnings
        testPlayer = Player.get(testUser); // Reload player
        assertTrue(testPlayer.getCoins() > 900, "Player should have received winnings");
        
        // Verify bet was marked as processed
        List<Bet> bets = db.getBetsForMatch(testMatch);
        assertEquals(1, bets.size());
        assertTrue(bets.get(0).isProcessed());
    }
    
    @Test
    public void testLoseBet() {
        // Place a bet
        Bet bet = new Bet(testMatch.getID(), testPlayer.getID(), "blue", 100);
        bet.save();
        
        // Deduct coins from player
        testPlayer.setCoins(900);
        testPlayer.save();
        
        // Set match result (red team wins, blue loses)
        testMatch.setRedScore(5);
        testMatch.setBlueScore(2);
        testMatch.setState(MatchState.FINISHED);
        testMatch.save();
        
        // Process bet results
        db.processBetsForMatch(testMatch);
        
        // Verify player coins remain the same (already deducted when bet was placed)
        testPlayer = Player.get(testUser); // Reload player
        assertEquals(900, testPlayer.getCoins());
        
        // Verify bet was marked as processed
        List<Bet> bets = db.getBetsForMatch(testMatch);
        assertEquals(1, bets.size());
        assertTrue(bets.get(0).isProcessed());
    }
}