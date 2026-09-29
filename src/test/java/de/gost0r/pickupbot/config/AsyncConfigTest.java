package de.gost0r.pickupbot.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncConfigTest {

    @Test
    void queueExecutorRunsTasksSeriallyInSubmissionOrder() throws Exception {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) new AsyncConfig().queueExecutor();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(2);
        List<Integer> order = new CopyOnWriteArrayList<>();

        try {
            executor.execute(() -> {
                order.add(1);
                firstStarted.countDown();
                await(releaseFirst);
                completed.countDown();
            });
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

            executor.execute(() -> {
                order.add(2);
                secondStarted.countDown();
                completed.countDown();
            });

            assertFalse(secondStarted.await(200, TimeUnit.MILLISECONDS));
            releaseFirst.countDown();
            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertEquals(List.of(1, 2), order);
        } finally {
            releaseFirst.countDown();
            executor.shutdown();
        }
    }

    @Test
    void commandExecutorDoesNotBlockIndependentTasks() throws Exception {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) new AsyncConfig().commandExecutor();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondCompleted = new CountDownLatch(1);

        try {
            executor.execute(() -> {
                firstStarted.countDown();
                await(releaseFirst);
            });
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

            executor.execute(secondCompleted::countDown);
            assertTrue(secondCompleted.await(2, TimeUnit.SECONDS));
        } finally {
            releaseFirst.countDown();
            executor.shutdown();
        }
    }

    @Test
    void pickPromptsCanRunWhileJoinValidationIsBlocked() throws Exception {
        ThreadPoolTaskExecutor joins = (ThreadPoolTaskExecutor) new AsyncConfig().pickupIoExecutor();
        ThreadPoolTaskExecutor picks = (ThreadPoolTaskExecutor) new AsyncConfig().pickIoExecutor();
        CountDownLatch joinStarted = new CountDownLatch(1);
        CountDownLatch releaseJoin = new CountDownLatch(1);
        CountDownLatch pickCompleted = new CountDownLatch(1);

        try {
            joins.execute(() -> {
                joinStarted.countDown();
                await(releaseJoin);
            });
            assertTrue(joinStarted.await(2, TimeUnit.SECONDS));
            picks.execute(pickCompleted::countDown);
            assertTrue(pickCompleted.await(2, TimeUnit.SECONDS));
        } finally {
            releaseJoin.countDown();
            joins.shutdown();
            picks.shutdown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
