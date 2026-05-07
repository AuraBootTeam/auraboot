package com.auraboot.framework.automation.event;

import com.auraboot.framework.agent.dto.LlmChunk;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * Asynchronous fan-out for {@link AutomationLlmChunkEvent} (E.1 Phase 1).
 *
 * <p>{@code LlmCallExecutor} calls {@link #publish} on its own thread; this
 * bean re-dispatches via a bounded {@code llmStreamExecutor}
 * ({@code core=2 / max=4 / queue=256}) configured with
 * {@code DiscardOldestPolicy}. When the queue is full the oldest chunk is
 * dropped — per spec Q8, dropping does NOT bubble to the caller (the LLM
 * call must still complete and write the aggregated {@code ${outputVariable}}).
 * Each drop increments {@code aura_workflow_stream_chunk_dropped_total} so
 * operators (and the admin UI) can detect lossy fan-out.
 *
 * <p>SSE subscribers register via {@link #subscribe} keyed on
 * {@code (runPid, nodeId)}; their callbacks run on the async executor too,
 * which keeps the LLM call thread free of slow-consumer back-pressure.
 *
 * <p>Per Q11 events are NOT persisted — this fan-out is in-memory only.
 * Subscribers that join after a chunk has fired do not see it (Q4: clients
 * reconnect from the start; we cannot replay).
 */
@Slf4j
@Component
public class AutomationRunStreamPublisher {

    /** Subscriber registry keyed on {@code "runPid::nodeId"}. */
    private final Map<String, CopyOnWriteArrayList<Subscription>> subscribers = new ConcurrentHashMap<>();

    /** Per-(runPid,nodeId) accumulated drop counter for the SSE done envelope. */
    private final Map<String, AtomicLong> droppedPerKey = new ConcurrentHashMap<>();

    private final Counter droppedCounter;
    private final Executor streamExecutor;

    @Autowired
    public AutomationRunStreamPublisher(MeterRegistry meterRegistry,
                                        @Qualifier("llmStreamExecutor") Executor streamExecutor) {
        this.streamExecutor = streamExecutor;
        this.droppedCounter = Counter.builder("aura_workflow_stream_chunk_dropped_total")
                .description("Number of LLM streaming chunks dropped due to bounded fan-out queue overflow (E.1).")
                .register(meterRegistry);
    }

    /**
     * Synchronous publish entry point used by {@code LlmCallExecutor}.
     * Submits each chunk fan-out as a task on the bounded
     * {@code llmStreamExecutor}. Direct executor submission (rather than
     * a self-call to an {@code @Async} method) is intentional — Spring's
     * AOP proxy is bypassed on internal self-calls, so we'd silently lose
     * the bounded-queue + DiscardOldestPolicy contract. Drops are non-fatal:
     * if the executor still rejects (after the rejection handler ran) we
     * count it and continue without bubbling to the LLM call thread.
     */
    public void publish(AutomationLlmChunkEvent event) {
        try {
            streamExecutor.execute(() -> publishToSubscribers(event));
        } catch (RejectedExecutionException ex) {
            recordDrop(event);
            log.debug("LLM chunk fan-out dropped synchronously: {}", ex.getMessage());
        }
    }

    private void publishToSubscribers(AutomationLlmChunkEvent event) {
        String key = key(event.runPid(), event.nodeId());
        CopyOnWriteArrayList<Subscription> list = subscribers.get(key);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (Subscription sub : list) {
            try {
                sub.consumer.accept(event.chunk(), event.chunkSeq());
            } catch (Exception e) {
                // Subscriber callbacks must not bubble — they may be slow
                // SSE emitters whose connection died mid-flight.
                log.debug("LLM stream subscriber threw on chunk seq={}: {}", event.chunkSeq(), e.getMessage());
            }
        }
    }

    /**
     * Register an SSE consumer for a particular (runPid, nodeId) pair.
     * Returns a handle the caller invokes on disconnect to unsubscribe — there
     * is no replay, so unsubscribed clients simply lose the stream until they
     * reconnect (and per Q4 they restart from chunk 0 conceptually, even
     * though we never replay buffered chunks).
     */
    public Subscription subscribe(String runPid, String nodeId, BiConsumer<LlmChunk, Long> consumer) {
        String key = key(runPid, nodeId);
        Subscription sub = new Subscription(this, key, consumer);
        subscribers.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(sub);
        return sub;
    }

    /**
     * Snapshot of the dropped-chunk counter for a given (runPid, nodeId).
     * Used by the SSE controller to attach {@code droppedCount} to the
     * terminal {@code done} envelope so the admin UI can render the warning
     * badge.
     */
    public long getDroppedCount(String runPid, String nodeId) {
        AtomicLong c = droppedPerKey.get(key(runPid, nodeId));
        return c == null ? 0L : c.get();
    }

    /**
     * Called by the SSE controller (or test harness) when fan-out is rejected
     * by the bounded executor. Bumped from {@link #publish} on
     * RejectedExecutionException, and also from the executor's
     * {@code DiscardOldestPolicy} via {@link #recordDropFromPolicy}.
     */
    void recordDrop(AutomationLlmChunkEvent event) {
        droppedCounter.increment();
        if (event != null) {
            droppedPerKey
                    .computeIfAbsent(key(event.runPid(), event.nodeId()), k -> new AtomicLong())
                    .incrementAndGet();
        }
    }

    /**
     * Hook called by {@link DroppingExecutorRejectionHandler} when
     * DiscardOldestPolicy evicts a queued task. We can't recover the original
     * event payload from a Runnable, so we only bump the global counter —
     * the per-key counter stays at the last known value, which understates
     * but never overstates the loss.
     */
    void recordDropFromPolicy() {
        droppedCounter.increment();
    }

    private static String key(String runPid, String nodeId) {
        return runPid + "::" + (nodeId == null ? "" : nodeId);
    }

    /** Returned by {@link #subscribe} so callers can clean up on disconnect. */
    public static final class Subscription {
        private final AutomationRunStreamPublisher owner;
        private final String key;
        private final BiConsumer<LlmChunk, Long> consumer;

        private Subscription(AutomationRunStreamPublisher owner, String key, BiConsumer<LlmChunk, Long> consumer) {
            this.owner = owner;
            this.key = key;
            this.consumer = consumer;
        }

        public void unsubscribe() {
            CopyOnWriteArrayList<Subscription> list = owner.subscribers.get(key);
            if (list != null) {
                list.remove(this);
            }
        }
    }
}
