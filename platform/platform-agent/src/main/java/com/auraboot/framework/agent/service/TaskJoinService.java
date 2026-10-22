package com.auraboot.framework.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Task-level analog of {@link ParentJoinService}: lets a waiting thread block
 * on "task X reached a terminal state" instead of sleeping a fixed backoff
 * interval, by latching on {@link AgentTaskCompletedEvent}.
 *
 * <p><strong>Latency optimization, not a correctness dependency.</strong>
 * Spring events are in-process only; in multi-instance deployments the task
 * may complete on another node and no event arrives here. Callers therefore
 * keep their DB poll as the authority and use {@link #awaitCompletion} only
 * to replace blind {@code Thread.sleep} between polls — a signal wakes the
 * poll immediately, a missed signal degrades to the existing poll cadence.
 */
@Slf4j
@Service
public class TaskJoinService {

    /**
     * One latch per task pid, shared by all concurrent waiters of that task.
     * Slots are registered on first wait and removed by whichever waiter
     * finishes first, so the map cannot grow unboundedly.
     */
    private final ConcurrentHashMap<String, CountDownLatch> waiters = new ConcurrentHashMap<>();

    @EventListener
    public void onTaskCompleted(AgentTaskCompletedEvent event) {
        CountDownLatch latch = waiters.get(event.getTaskPid());
        if (latch != null) {
            latch.countDown();
        }
    }

    /**
     * Wait up to {@code timeoutMs} for the in-JVM completion signal of the task.
     *
     * @return true when the signal arrived within the timeout; false on timeout
     *         or interrupt. A false return means "poll the DB as usual", never
     *         "the task is still running".
     */
    public boolean awaitCompletion(String taskPid, long timeoutMs) {
        CountDownLatch latch = waiters.computeIfAbsent(taskPid, k -> new CountDownLatch(1));
        try {
            return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            waiters.remove(taskPid, latch);
        }
    }
}
