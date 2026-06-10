package com.auraboot.framework.agent.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TaskJoinService} — completion signal wakes waiters,
 * timeout degrades to false (caller polls), slots are cleaned up.
 */
class TaskJoinServiceTest {

    private final TaskJoinService service = new TaskJoinService();

    @Test
    void completionSignalWakesWaiterImmediately() throws Exception {
        CompletableFuture<Boolean> waiter = CompletableFuture.supplyAsync(
                () -> service.awaitCompletion("task-1", 5000));

        // Give the waiter a moment to register its latch
        Thread.sleep(150);
        service.onTaskCompleted(new AgentTaskCompletedEvent(1L, "task-1", null, "done"));

        assertThat(waiter.get(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void timeoutReturnsFalseSoCallerPolls() {
        long start = System.currentTimeMillis();
        boolean signaled = service.awaitCompletion("task-2", 200);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(signaled).isFalse();
        assertThat(elapsed).isGreaterThanOrEqualTo(180);
    }

    @Test
    void signalForDifferentTaskDoesNotWakeWaiter() throws Exception {
        CompletableFuture<Boolean> waiter = CompletableFuture.supplyAsync(
                () -> service.awaitCompletion("task-3", 400));

        Thread.sleep(100);
        service.onTaskCompleted(new AgentTaskCompletedEvent(1L, "other-task", null, "done"));

        assertThat(waiter.get(2, TimeUnit.SECONDS)).isFalse();
    }

    @Test
    void multipleWaitersOnSameTaskAllWake() throws Exception {
        CompletableFuture<Boolean> w1 = CompletableFuture.supplyAsync(
                () -> service.awaitCompletion("task-4", 5000));
        CompletableFuture<Boolean> w2 = CompletableFuture.supplyAsync(
                () -> service.awaitCompletion("task-4", 5000));

        Thread.sleep(150);
        service.onTaskCompleted(new AgentTaskCompletedEvent(1L, "task-4", "parent-x", "failed"));

        assertThat(w1.get(2, TimeUnit.SECONDS)).isTrue();
        assertThat(w2.get(2, TimeUnit.SECONDS)).isTrue();
    }
}
