package de.gost0r.pickupbot.pickup;

import de.gost0r.pickupbot.discord.DiscordUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlayerConcurrencyTest {

    @BeforeEach
    void setUp() throws Exception {
        playerCache().clear();
        Player.db = mock(Database.class);
    }

    @AfterEach
    void tearDown() throws Exception {
        playerCache().clear();
    }

    @Test
    void concurrentRegistrationAndRemovalLeavesNoDuplicateCacheEntries() throws Exception {
        int taskCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Player> players = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < taskCount; i++) {
                DiscordUser user = mock(DiscordUser.class);
                when(user.getId()).thenReturn("same-user");
                futures.add(executor.submit(() -> {
                    start.await();
                    players.add(new Player(user, "same-auth"));
                    return null;
                }));
            }

            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
            assertEquals(taskCount, playerCache().size());

            Player.remove(players.get(0));

            assertEquals(0, playerCache().size());
            assertNull(Player.get("same-auth"));
        } finally {
            executor.shutdownNow();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Player> playerCache() throws Exception {
        Field field = Player.class.getDeclaredField("playerList");
        field.setAccessible(true);
        return (List<Player>) field.get(null);
    }
}
