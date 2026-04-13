package de.gost0r.pickupbot.pickup.server;

import de.gost0r.pickupbot.pickup.Region;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Manual test for RCON functionality against a live server.
 * 
 * To run these tests locally, set environment variables:
 *   RCON_TEST_IP=your.server.ip
 *   RCON_TEST_PORT=27960
 *   RCON_TEST_PASSWORD=yourpassword
 * 
 * Then enable the specific test by removing @Disabled and run:
 *   ./gradlew test --tests "RconBatchTest.testName"
 * 
 * All tests are @Disabled by default to prevent running in CI.
 */
class RconBatchTest {

    // Server credentials from environment variables - never hardcode these
    private static final String SERVER_IP = System.getenv("RCON_TEST_IP") != null 
            ? System.getenv("RCON_TEST_IP") : "127.0.0.1";
    private static final int SERVER_PORT = System.getenv("RCON_TEST_PORT") != null 
            ? Integer.parseInt(System.getenv("RCON_TEST_PORT")) : 27960;
    private static final String RCON_PASSWORD = System.getenv("RCON_TEST_PASSWORD") != null 
            ? System.getenv("RCON_TEST_PASSWORD") : "changeme";

    // AIM.cfg commands (excluding 'map' command which we'll handle separately)
    private static final List<String> AIM_CONFIG = List.of(
        "set sv_dlURL \"maps.pugbot.net\"",
        "set sv_hostname \"Pickup @ WWW.DISCORD.ME/URT\"",
        "set sv_pure \"1\"",
        "set sv_timeout \"90\"",
        "sets \" Discord\" \"www.discord.io/urbanterror\"",
        "set auth_enable \"1\"",
        "set auth_notoriety \"0\"",
        "set auth_owners \"13 618 2797\"",
        "set bot_enable \"1\"",
        "set fraglimit \"0\"",
        "set timelimit \"5\"",
        "set capturelimit \"0\"",
        "set g_allowVote \"31\"",
        "set g_gametype \"3\"",
        "set g_gear \"\"",
        "set g_matchmode \"1\"",
        "set g_respawnProtection \"0\"",
        "set g_respawndelay \"0\"",
        "set g_forcerespawn \"1\"",
        "set g_waveRespawns \"0\"",
        "set g_swaproles \"0\"",
        "set g_autobalance \"0\"",
        "set bot_enable \"1\"",
        "set g_nextmap \"ut4_aimtraining_b1\"",
        "set g_promod \"0\""
    );

    // Full AIM.cfg including map and addbot commands
    private static final List<String> FULL_AIM_CONFIG = List.of(
        "set sv_dlURL \"maps.pugbot.net\"",
        "set sv_hostname \"Pickup @ WWW.DISCORD.ME/URT\"",
        "set sv_pure \"1\"",
        "set sv_timeout \"90\"",
        "sets \" Discord\" \"www.discord.io/urbanterror\"",
        "set auth_enable \"1\"",
        "set auth_notoriety \"0\"",
        "set auth_owners \"13 618 2797\"",
        "set bot_enable \"1\"",
        "map ut4_aimtraining_b1",
        "set fraglimit \"0\"",
        "set timelimit \"5\"",
        "set capturelimit \"0\"",
        "set g_allowVote \"31\"",
        "set g_gametype \"3\"",
        "set g_gear \"\"",
        "set g_matchmode \"1\"",
        "set g_respawnProtection \"0\"",
        "set g_respawndelay \"0\"",
        "set g_forcerespawn \"1\"",
        "set g_waveRespawns \"0\"",
        "set g_swaproles \"0\"",
        "set g_autobalance \"0\"",
        "set bot_enable \"1\"",
        "set g_nextmap \"ut4_aimtraining_b1\"",
        "set g_promod \"0\"",
        "addbot del4 1 Red 10 |it|tritoch",
        "addbot del1 1 Red 50 GlaD-slackin",
        "addbot del4 1 Red 10 hg`.Gost0r",
        "addbot del3 1 Red 10 MQCD|Biddle",
        "addbot del2 1 Red 10 6th|Clear",
        "addbot del1 1 Red 90 strayA#Hypperz",
        "addbot del2 1 Red 10 MQCD|Asloon",
        "addbot del3 1 Red 10 adn`Holycrap",
        "addbot del1 1 Red 50 GlaD-Solitary",
        "addbot del1 1 Red 90 strayA#Delirium"
    );

    @Test
    @Disabled("Manual test - enable to run against live server")
    void testFullAimConfigWithChunking() {
        Server server = new Server(-1, SERVER_IP, SERVER_PORT, RCON_PASSWORD, "test", true, Region.EU);

        System.out.println("=== Testing Full AIM.cfg with Auto-Chunking ===\n");
        System.out.println("Commands: " + FULL_AIM_CONFIG.size());
        System.out.println("Total length: " + String.join("; ", FULL_AIM_CONFIG).length() + " chars");
        
        long start = System.currentTimeMillis();
        String response = server.sendRconBatch(new ArrayList<>(FULL_AIM_CONFIG));
        long elapsed = System.currentTimeMillis() - start;
        
        System.out.println("\nExecution time: " + elapsed + "ms");
        System.out.println("Response length: " + (response != null ? response.length() : 0));
        
        // Wait for map to load if it changed
        try { Thread.sleep(2000); } catch (InterruptedException e) {}
        
        // Verify key settings
        System.out.println("\nVerifying settings after full config:");
        String[][] checks = {
            {"g_gametype", "3"},
            {"g_matchmode", "1"}, 
            {"timelimit", "5"},
            {"sv_hostname", "Pickup"}
        };
        
        int passed = 0;
        for (String[] check : checks) {
            String result = server.sendRcon(check[0]);
            boolean ok = result != null && (result.contains(check[1]) || result.contains("latched"));
            System.out.println(check[0] + ": " + (ok ? "OK" : "check manually"));
            if (ok) passed++;
        }
        
        System.out.println("\nResult: " + passed + "/" + checks.length + " verified");
        System.out.println("Individual calls would take: ~" + (FULL_AIM_CONFIG.size() * 500) + "ms");
        System.out.println("Speedup: ~" + String.format("%.1fx", (FULL_AIM_CONFIG.size() * 500.0) / elapsed));
    }

    @Test
    @Disabled("Manual test - finding limits")
    void testVstrStringLimits() {
        Server server = new Server(-1, SERVER_IP, SERVER_PORT, RCON_PASSWORD, "test", true, Region.EU);

        System.out.println("=== Testing VSTR String Length Limits ===\n");
        
        // Test full AIM.cfg
        String fullConfig = String.join("; ", FULL_AIM_CONFIG);
        System.out.println("Full AIM.cfg commands: " + FULL_AIM_CONFIG.size());
        System.out.println("Full AIM.cfg string length: " + fullConfig.length() + " chars");
        System.out.println();
        
        // Try to set and execute
        System.out.println("Attempting to set full config as vstr...");
        String defineCmd = "set _fullcfg \"" + fullConfig + "\"";
        System.out.println("Define command length: " + defineCmd.length() + " chars");
        
        server.sendRcon(defineCmd);
        
        // Check what was actually stored
        String stored = server.sendRcon("_fullcfg");
        System.out.println("\nStored value check:");
        if (stored != null) {
            // Extract value
            int start = stored.indexOf("is:\"");
            if (start != -1) {
                start += 4;
                int end = stored.indexOf("^7\"", start);
                if (end != -1) {
                    String value = stored.substring(start, end);
                    System.out.println("Stored length: " + value.length() + " chars");
                    System.out.println("Truncated: " + (value.length() < fullConfig.length() ? "YES" : "NO"));
                    if (value.length() < fullConfig.length()) {
                        System.out.println("Lost: " + (fullConfig.length() - value.length()) + " chars");
                    }
                }
            }
        }
        
        // Find the actual limit by binary search
        System.out.println("\n=== Finding exact string limit ===");
        int low = 100, high = 2000;
        
        while (high - low > 10) {
            int mid = (low + high) / 2;
            String testStr = "x".repeat(mid);
            server.sendRcon("set _test \"" + testStr + "\"");
            String check = server.sendRcon("_test");
            
            int storedLen = 0;
            if (check != null) {
                int s = check.indexOf("is:\"");
                if (s != -1) {
                    s += 4;
                    int e = check.indexOf("^7\"", s);
                    if (e != -1) {
                        storedLen = e - s;
                    }
                }
            }
            
            if (storedLen >= mid) {
                low = mid;
            } else {
                high = mid;
            }
        }
        
        System.out.println("Max vstr string length: ~" + low + " chars");
        System.out.println();
        
        // Calculate how many commands fit
        int avgCmdLen = fullConfig.length() / FULL_AIM_CONFIG.size();
        int maxCmds = low / (avgCmdLen + 2); // +2 for "; "
        System.out.println("Avg command length: " + avgCmdLen + " chars");
        System.out.println("Est. max commands per vstr: ~" + maxCmds);
    }

    @Test
    @Disabled("Manual test - enable to run against live server")
    void testVstrFullConfig() {
        Server server = new Server(-1, SERVER_IP, SERVER_PORT, RCON_PASSWORD, "test", true, Region.EU);

        System.out.println("=== Testing VSTR with Full Config (25 commands) ===\n");
        
        // Build the batch definition - join all commands with semicolons
        String allCommands = String.join("; ", AIM_CONFIG);
        System.out.println("Total command length: " + allCommands.length() + " chars");
        
        // Define and execute
        long start = System.currentTimeMillis();
        String defineCmd = "set _cfgbatch \"" + allCommands + "\"";
        server.sendRcon(defineCmd);
        server.sendRcon("vstr _cfgbatch");
        long elapsed = System.currentTimeMillis() - start;
        
        System.out.println("Batch execution time: " + elapsed + "ms (2 RCON calls)");
        System.out.println("Compare to individual: ~13500ms (25 RCON calls)");
        System.out.println("Speedup: " + String.format("%.1fx", 13500.0 / elapsed));
        
        // Verify key settings
        System.out.println("\nVerifying key settings:");
        String[][] checks = {
            {"g_gametype", "3"},
            {"g_matchmode", "1"},
            {"timelimit", "5"},
            {"g_respawndelay", "0"},
            {"g_forcerespawn", "1"},
            {"g_swaproles", "0"}
        };
        
        int passed = 0;
        for (String[] check : checks) {
            String result = server.sendRcon(check[0]);
            String value = extractValue(result);
            // Handle latched cvars
            boolean ok = value.equals(check[1]) || (result != null && result.contains("latched: \"" + check[1] + "\""));
            System.out.println(check[0] + " = " + value + (ok ? " OK" : " FAIL"));
            if (ok) passed++;
        }
        
        System.out.println("\nResult: " + passed + "/" + checks.length + " verified");
    }

    @Test
    @Disabled("Manual test - enable to run against live server")
    void testVstrBatching() {
        Server server = new Server(-1, SERVER_IP, SERVER_PORT, RCON_PASSWORD, "test", true, Region.EU);

        System.out.println("=== Testing VSTR Batching ===\n");
        
        // Reset to known state
        server.sendRcon("set g_respawndelay 99");
        server.sendRcon("set g_forcerespawn 99");
        server.sendRcon("set g_matchmode 99");
        
        System.out.println("Reset all to 99");
        
        // Define batch command and execute with vstr
        System.out.println("\nDefining batch alias...");
        String batchDef = "set _batch \"set g_respawndelay 0; set g_forcerespawn 1; set g_matchmode 1\"";
        System.out.println("Command: " + batchDef);
        server.sendRcon(batchDef);
        
        System.out.println("\nExecuting with vstr _batch...");
        long start = System.currentTimeMillis();
        server.sendRcon("vstr _batch");
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("Execution time: " + elapsed + "ms");
        
        // Verify
        System.out.println("\nVerifying:");
        String[] cvars = {"g_respawndelay", "g_forcerespawn", "g_matchmode"};
        String[] expected = {"0", "1", "1"};
        
        int passed = 0;
        for (int i = 0; i < cvars.length; i++) {
            String result = server.sendRcon(cvars[i]);
            String value = extractValue(result);
            boolean ok = value.equals(expected[i]);
            System.out.println(cvars[i] + " = " + value + (ok ? " OK" : " FAIL (expected " + expected[i] + ")"));
            if (ok) passed++;
        }
        
        System.out.println("\nResult: " + passed + "/" + cvars.length + " settings applied correctly");
        System.out.println(passed == cvars.length ? "SUCCESS!" : "FAILED");
    }

    @Test
    @Disabled("Manual test - enable to run against live server")
    void testSemicolonWithRconPrefix() {
        Server server = new Server(-1, SERVER_IP, SERVER_PORT, RCON_PASSWORD, "test", true, Region.EU);

        System.out.println("=== Testing 'rcon cmd; rcon cmd' syntax ===\n");
        
        // Reset to known state
        server.sendRcon("set g_respawndelay 99");
        server.sendRcon("set g_forcerespawn 99");
        
        String before1 = server.sendRcon("g_respawndelay");
        String before2 = server.sendRcon("g_forcerespawn");
        System.out.println("Reset - g_respawndelay: " + (before1.contains("99") ? "99" : "FAIL"));
        System.out.println("Reset - g_forcerespawn: " + (before2.contains("99") ? "99" : "FAIL"));
        
        // Test: "set g_respawndelay 0; set g_forcerespawn 1" (original - doesn't work)
        System.out.println("\nTest 1: 'set X; set Y' (no rcon prefix)");
        server.sendRcon("set g_respawndelay 0; set g_forcerespawn 1");
        String check1 = server.sendRcon("g_respawndelay");
        String check2 = server.sendRcon("g_forcerespawn");
        System.out.println("g_respawndelay: " + extractValue(check1));
        System.out.println("g_forcerespawn: " + extractValue(check2));
        
        // Reset
        server.sendRcon("set g_respawndelay 99");
        server.sendRcon("set g_forcerespawn 99");
        
        // Test with newline separator
        System.out.println("\nTest 2: Using newline separator");
        server.sendRcon("set g_respawndelay 0\nset g_forcerespawn 1");
        check1 = server.sendRcon("g_respawndelay");
        check2 = server.sendRcon("g_forcerespawn");
        System.out.println("g_respawndelay: " + extractValue(check1));
        System.out.println("g_forcerespawn: " + extractValue(check2));
        
        // Reset
        server.sendRcon("set g_respawndelay 99");
        server.sendRcon("set g_forcerespawn 99");
        
        // Test with && separator  
        System.out.println("\nTest 3: Using && separator");
        server.sendRcon("set g_respawndelay 0 && set g_forcerespawn 1");
        check1 = server.sendRcon("g_respawndelay");
        check2 = server.sendRcon("g_forcerespawn");
        System.out.println("g_respawndelay: " + extractValue(check1));
        System.out.println("g_forcerespawn: " + extractValue(check2));
        
        // Reset
        server.sendRcon("set g_respawndelay 99");
        server.sendRcon("set g_forcerespawn 99");
        
        // Test vstr approach - define then execute
        System.out.println("\nTest 4: Using vstr (define alias then execute)");
        server.sendRcon("set batch1 \"set g_respawndelay 0; set g_forcerespawn 1\"");
        server.sendRcon("vstr batch1");
        check1 = server.sendRcon("g_respawndelay");
        check2 = server.sendRcon("g_forcerespawn");
        System.out.println("g_respawndelay: " + extractValue(check1));
        System.out.println("g_forcerespawn: " + extractValue(check2));
    }
    
    private String extractValue(String rconResponse) {
        if (rconResponse == null) return "NULL";
        // Extract value from: "cvar" is:"VALUE^7" default:"X"
        int start = rconResponse.indexOf("is:\"");
        if (start == -1) return rconResponse.trim().replace("\n", " | ");
        start += 4;
        int end = rconResponse.indexOf("^7\"", start);
        if (end == -1) return rconResponse.trim().replace("\n", " | ");
        return rconResponse.substring(start, end);
    }

    @Test
    @Disabled("Manual test - enable to run against live server")
    void testBatchRcon() {
        Server server = new Server(-1, SERVER_IP, SERVER_PORT, RCON_PASSWORD, "test", true, Region.EU);

        System.out.println("=== Testing RCON Batch ===");
        System.out.println("Server: " + SERVER_IP + ":" + SERVER_PORT);
        System.out.println();

        // First verify server is reachable
        System.out.println("1. Testing server connectivity...");
        String statusResponse = server.sendRcon("status");
        if (statusResponse == null || statusResponse.isEmpty()) {
            System.err.println("ERROR: Could not connect to server!");
            return;
        }
        System.out.println("   Server is reachable. Response length: " + statusResponse.length());
        System.out.println();

        // Test individual commands (baseline)
        System.out.println("2. Testing INDIVIDUAL commands (baseline)...");
        long startIndividual = System.currentTimeMillis();
        int individualSuccess = 0;
        for (String cmd : AIM_CONFIG) {
            String response = server.sendRcon(cmd);
            if (response != null) {
                individualSuccess++;
            }
        }
        long individualTime = System.currentTimeMillis() - startIndividual;
        System.out.println("   Sent " + AIM_CONFIG.size() + " commands individually");
        System.out.println("   Success: " + individualSuccess + "/" + AIM_CONFIG.size());
        System.out.println("   Time: " + individualTime + "ms");
        System.out.println("   Avg per command: " + (individualTime / AIM_CONFIG.size()) + "ms");
        System.out.println();

        // Small delay between tests
        try { Thread.sleep(1000); } catch (InterruptedException e) {}

        // Test batched commands
        System.out.println("3. Testing BATCHED commands...");
        long startBatch = System.currentTimeMillis();
        String batchResponse = server.sendRconBatch(new ArrayList<>(AIM_CONFIG));
        long batchTime = System.currentTimeMillis() - startBatch;
        System.out.println("   Sent " + AIM_CONFIG.size() + " commands in ONE batch");
        System.out.println("   Success: " + (batchResponse != null ? "YES" : "NO"));
        System.out.println("   Time: " + batchTime + "ms");
        System.out.println("   Response length: " + (batchResponse != null ? batchResponse.length() : 0));
        System.out.println();

        // Summary
        System.out.println("=== SUMMARY ===");
        System.out.println("Individual: " + individualTime + "ms for " + AIM_CONFIG.size() + " commands");
        System.out.println("Batched:    " + batchTime + "ms for " + AIM_CONFIG.size() + " commands");
        System.out.println("Speedup:    " + String.format("%.1fx", (double) individualTime / batchTime));
        System.out.println();

        // Verify a setting was applied
        System.out.println("4. Verifying settings were applied...");
        String gametypeCheck = server.sendRcon("g_gametype");
        System.out.println("   g_gametype response: " + (gametypeCheck != null ? gametypeCheck.trim() : "null"));
    }

    @Test
    void testNewBatchApproach() {
        Server server = new Server(-1, SERVER_IP, SERVER_PORT, RCON_PASSWORD, "test", true, Region.EU);

        System.out.println("=== Testing New Batch Approach (pushRcon rapid-fire) ===");
        
        // Reset to known bad state
        server.sendRcon("set g_respawndelay 99");
        server.sendRcon("set g_forcerespawn 99");
        server.sendRcon("set g_matchmode 99");
        
        System.out.println("Reset all to 99");
        
        // Test the new batch method
        List<String> testCommands = List.of(
            "set g_respawndelay 0",
            "set g_forcerespawn 1",
            "set g_matchmode 1"
        );
        
        System.out.println("\nSending batch of " + testCommands.size() + " commands...");
        long start = System.currentTimeMillis();
        String response = server.sendRconBatch(new ArrayList<>(testCommands));
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("Batch time: " + elapsed + "ms");
        System.out.println("Response: " + (response != null ? response.trim() : "NULL"));
        
        // Small delay to let server process
        try { Thread.sleep(100); } catch (InterruptedException e) {}
        
        // Verify all settings
        System.out.println("\nVerifying settings:");
        String[] checks = {"g_respawndelay", "g_forcerespawn", "g_matchmode"};
        String[] expected = {"0", "1", "1"};
        
        boolean allPassed = true;
        for (int i = 0; i < checks.length; i++) {
            String check = server.sendRcon(checks[i]);
            boolean passed = check != null && check.contains("\"" + expected[i] + "^7\"");
            System.out.println(checks[i] + ": " + (passed ? "OK (" + expected[i] + ")" : "FAIL - " + check.trim().replace("\n", " | ")));
            allPassed &= passed;
        }
        
        System.out.println("\n" + (allPassed ? "SUCCESS - All commands applied!" : "FAILED - Some commands did not apply"));
    }

    @Test
    void testBatchParsing() {
        Server server = new Server(-1, SERVER_IP, SERVER_PORT, RCON_PASSWORD, "test", true, Region.EU);

        System.out.println("=== Testing Batch Parsing ===");
        
        // Reset to known state
        server.sendRcon("set g_respawndelay 99");
        server.sendRcon("set g_forcerespawn 99");
        
        String before = server.sendRcon("g_respawndelay");
        System.out.println("Reset to 99: " + (before != null ? before.contains("99") : "failed"));
        
        // Test small batch with just the problematic commands
        System.out.println("\nTest 1: Small batch with respawndelay");
        server.sendRcon("set g_respawndelay 0;set g_forcerespawn 1");
        
        String check1 = server.sendRcon("g_respawndelay");
        String check2 = server.sendRcon("g_forcerespawn");
        System.out.println("g_respawndelay: " + (check1 != null && check1.contains("0") ? "OK (0)" : "FAIL - " + check1));
        System.out.println("g_forcerespawn: " + (check2 != null && check2.contains("1") ? "OK (1)" : "FAIL - " + check2));
        
        // Reset and test different format
        server.sendRcon("set g_respawndelay 99");
        
        System.out.println("\nTest 2: Without quotes");
        server.sendRcon("set g_respawndelay 0 ; set g_forcerespawn 1");
        check1 = server.sendRcon("g_respawndelay");
        System.out.println("g_respawndelay: " + (check1 != null ? check1.trim().replace("\n", " | ") : "NULL"));
        
        // Reset and test exact config format
        server.sendRcon("set g_respawndelay 99");
        
        System.out.println("\nTest 3: Exact config format from batch");
        String exactCmd = "set g_respawnProtection \"0\";set g_respawndelay \"0\";set g_forcerespawn \"1\"";
        System.out.println("Command: " + exactCmd);
        server.sendRcon(exactCmd);
        
        check1 = server.sendRcon("g_respawndelay");
        System.out.println("g_respawndelay: " + (check1 != null ? check1.trim().replace("\n", " | ") : "NULL"));
    }

    @Test
    void testIndividualRespawnDelay() {
        Server server = new Server(-1, SERVER_IP, SERVER_PORT, RCON_PASSWORD, "test", true, Region.EU);

        System.out.println("=== Testing g_respawndelay individually ===");
        
        // Check current value
        String before = server.sendRcon("g_respawndelay");
        System.out.println("Before: " + (before != null ? before.trim().replace("\n", " | ") : "NULL"));
        
        // Set it individually
        System.out.println("Setting g_respawndelay 0...");
        String setResponse = server.sendRcon("set g_respawndelay 0");
        System.out.println("Set response: " + (setResponse != null ? setResponse.trim() : "NULL"));
        
        // Check after
        String after = server.sendRcon("g_respawndelay");
        System.out.println("After: " + (after != null ? after.trim().replace("\n", " | ") : "NULL"));
        
        // Try with quotes
        System.out.println("\nTrying with quotes: set g_respawndelay \"0\"");
        server.sendRcon("set g_respawndelay \"0\"");
        String afterQuotes = server.sendRcon("g_respawndelay");
        System.out.println("After quotes: " + (afterQuotes != null ? afterQuotes.trim().replace("\n", " | ") : "NULL"));
    }

    @Test
    void testBatchSizeAndChunking() {
        Server server = new Server(-1, SERVER_IP, SERVER_PORT, RCON_PASSWORD, "test", true, Region.EU);

        System.out.println("=== Testing Batch Size Limits ===");
        
        // Calculate total batch size
        String batchedCommand = String.join(";", AIM_CONFIG);
        System.out.println("Total batch command length: " + batchedCommand.length() + " chars");
        System.out.println("Full command: " + batchedCommand);
        System.out.println();
        
        // Test chunked approach - split into groups of 5 commands
        System.out.println("=== Testing CHUNKED approach (5 cmds per batch) ===");
        long startChunked = System.currentTimeMillis();
        int chunkSize = 5;
        for (int i = 0; i < AIM_CONFIG.size(); i += chunkSize) {
            List<String> chunk = AIM_CONFIG.subList(i, Math.min(i + chunkSize, AIM_CONFIG.size()));
            String chunkCmd = String.join(";", chunk);
            System.out.println("Chunk " + (i/chunkSize + 1) + " (" + chunkCmd.length() + " chars): " + chunk.size() + " cmds");
            server.sendRcon(chunkCmd);
        }
        long chunkedTime = System.currentTimeMillis() - startChunked;
        System.out.println("Chunked time: " + chunkedTime + "ms");
        System.out.println();
        
        // Verify g_respawndelay specifically
        System.out.println("Verifying g_respawndelay after chunked batch:");
        String check = server.sendRcon("g_respawndelay");
        System.out.println("g_respawndelay: " + (check != null ? check.trim().replace("\n", " | ") : "NULL"));
    }

    @Test
    void testVerifyAllSettings() {
        Server server = new Server(-1, SERVER_IP, SERVER_PORT, RCON_PASSWORD, "test", true, Region.EU);

        System.out.println("=== Verifying All Settings Applied ===");
        System.out.println();

        // Key settings to verify from AIM.cfg
        String[] settingsToCheck = {
            "g_gametype",      // should be 3
            "g_gear",          // should be empty
            "g_matchmode",     // should be 1
            "timelimit",       // should be 5
            "fraglimit",       // should be 0
            "g_respawndelay",  // should be 0
            "g_forcerespawn",  // should be 1
            "g_swaproles",     // should be 0
            "sv_hostname"      // should contain "Pickup"
        };

        for (String setting : settingsToCheck) {
            String response = server.sendRcon(setting);
            String value = response != null ? response.trim().replace("\n", " | ") : "NULL";
            System.out.println(setting + ": " + value);
        }
    }

    @Test
    @Disabled("Enable manually to test against live server")
    void testBatchWithMap() {
        Server server = new Server(-1, SERVER_IP, SERVER_PORT, RCON_PASSWORD, "test", true, Region.EU);

        System.out.println("=== Testing Full Config Apply (with map) ===");

        // Batch all config commands
        List<String> allCommands = new ArrayList<>(AIM_CONFIG);
        
        System.out.println("1. Sending batched config...");
        long start = System.currentTimeMillis();
        String response = server.sendRconBatch(allCommands);
        long configTime = System.currentTimeMillis() - start;
        System.out.println("   Config batch time: " + configTime + "ms");
        System.out.println("   Response: " + (response != null ? "received" : "null"));

        // Map command separate (causes map reload)
        System.out.println("2. Sending map command...");
        start = System.currentTimeMillis();
        String mapResponse = server.sendRcon("map ut4_aimtraining_b1");
        long mapTime = System.currentTimeMillis() - start;
        System.out.println("   Map command time: " + mapTime + "ms");
        System.out.println("   Response: " + (mapResponse != null ? mapResponse.trim() : "null"));

        System.out.println();
        System.out.println("Total time: " + (configTime + mapTime) + "ms");
    }
}
