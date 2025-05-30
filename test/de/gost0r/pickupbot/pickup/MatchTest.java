package de.gost0r.pickupbot.pickup;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.gost0r.pickupbot.discord.DiscordUser;
import de.gost0r.pickupbot.discord.MockDiscordBot;

public class MatchTest {
    
    private MockDiscordBot mockBot;
    private Database db;
    private PickupLogic logic;
    private Player testPlayer1;
    private Player testPlayer2;
    private DiscordUser testUser1;
    private DiscordUser testUser2;
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
        Match.db = db;
        
        // Create test users and players
        testUser1 = new DiscordUser("123456789", "TestUser1", "1234");
        testPlayer1 = new Player(testUser1, "testplayer1");
        testPlayer1.save();
        
        testUser2 = new DiscordUser("987654321", "TestUser2", "4321");
        testPlayer2 = new Player(testUser2, "testplayer2");
        testPlayer2.save();
        
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
    public void testCreateMatch() {
        // Create a match
        Gametype gametype = new Gametype("ctf", 2, 5);
        Match match = new Match(1, gametype);
        match.save();
        
        // Verify match was saved
        Match retrievedMatch = db.getMatch(1);
        assertNotNull(retrievedMatch);
        assertEquals(1, retrievedMatch.getID());
        assertEquals(MatchState.PENDING, retrievedMatch.getState());
    }
    
    @Test
    public void testUpdateMatchState() {
        // Create a match
        Gametype gametype = new Gametype("ctf", 2, 5);
        Match match = new Match(1, gametype);
        match.save();
        
        // Update match state
        match.setState(MatchState.LIVE);
        match.save();
        
        // Verify match was updated
        Match retrievedMatch = db.getMatch(1);
        assertEquals(MatchState.LIVE, retrievedMatch.getState());
    }
    
    @Test
    public void testSetMatchScores() {
        // Create a match
        Gametype gametype = new Gametype("ctf", 2, 5);
        Match match = new Match(1, gametype);
        match.save();
        
        // Set scores and finish match
        match.setRedScore(5);
        match.setBlueScore(3);
        match.setState(MatchState.FINISHED);
        match.save();
        
        // Verify match was updated
        Match retrievedMatch = db.getMatch(1);
        assertEquals(5, retrievedMatch.getRedScore());
        assertEquals(3, retrievedMatch.getBlueScore());
        assertEquals(MatchState.FINISHED, retrievedMatch.getState());
    }
    
    @Test
    public void testAddPlayersToMatch() {
        // Create a match
        Gametype gametype = new Gametype("ctf", 2, 5);
        Match match = new Match(1, gametype);
        match.save();
        
        // Add players to teams
        match.addPlayerToTeam(testPlayer1, "red");
        match.addPlayerToTeam(testPlayer2, "blue");
        match.save();
        
        // Verify players were added
        Match retrievedMatch = db.getMatch(1);
        assertTrue(retrievedMatch.getTeam("red").contains(testPlayer1));
        assertTrue(retrievedMatch.getTeam("blue").contains(testPlayer2));
    }
    
    @Test
    public void testMatchResultCommand() {
        // Create a match
        Gametype gametype = new Gametype("ctf", 2, 5);
        Match match = new Match(1, gametype);
        
        // Add players to teams
        match.addPlayerToTeam(testPlayer1, "red");
        match.addPlayerToTeam(testPlayer2, "blue");
        
        // Set scores and finish match
        match.setRedScore(5);
        match.setBlueScore(3);
        match.setState(MatchState.FINISHED);
        match.save();
        
        // Test !matchresult command
        mockBot.simulateCommand("!matchresult 1", testUser1);
        
        // Verify response
        List<String> messages = mockBot.getChannelMessages(TEST_CHANNEL);
        assertEquals(1, messages.size());
        assertTrue(messages.get(0).contains("Match #1"));
        assertTrue(messages.get(0).contains("5:3"));
    }
    
    @Test
    public void testLastMatchCommand() {
        // Create multiple matches
        Gametype gametype = new Gametype("ctf", 2, 5);
        
        Match match1 = new Match(1, gametype);
        match1.setState(MatchState.FINISHED);
        match1.save();
        
        Match match2 = new Match(2, gametype);
        match2.setState(MatchState.FINISHED);
        match2.save();
        
        // Test !lastmatch command
        mockBot.simulateCommand("!lastmatch", testUser1);
        
        // Verify response
        List<String> messages = mockBot.getChannelMessages(TEST_CHANNEL);
        assertEquals(1, messages.size());
        assertTrue(messages.get(0).contains("Match #2"));
    }
}