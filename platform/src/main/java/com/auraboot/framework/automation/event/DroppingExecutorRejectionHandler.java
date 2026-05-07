package com.auraboot.framework.automation.event;

import org.springframework.context.ApplicationContext;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Hybrid rejection handler used by {@code llmStreamExecutor} (E.1 Phase 1).
 *
 * <p>Behaves identically to {@link ThreadPoolExecutor.DiscardOldestPolicy}
 * (drop the head-of-queue task and re-submit the new task), but additionally
 * notifies {@link AutomationRunStreamPublisher} so the
 * {@code aura_workflow_stream_chunk_dropped_total} counter reflects every
 * eviction. Per spec Q8, drops are silent at the LLM-call layer — they only
 * surface via the counter and via the {@code droppedCount} field on the
 * terminal SSE envelope.
 *
 * <p>The publisher is resolved lazily through
 * {@link ApplicationContext#getBean} because Spring builds the executor
 * before the publisher bean is fully initialised; the lazy lookup avoids the
 * obvious circular dependency.
 */
public class DroppingExecutorRejectionHandler implements RejectedExecutionHandler {

    private final ApplicationContext applicationContext;
    private volatile AutomationRunStreamPublisher cachedPublisher;

    public DroppingExecutorRejectionHandler(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
        if (!executor.isShutdown()) {
            // DiscardOldestPolicy semantics: poll the head, then offer the new
            // task. Track the eviction count via the publisher counter.
            Runnable evicted = executor.getQueue().poll();
            if (evicted != null) {
                AutomationRunStreamPublisher pub = resolvePublisher();
                if (pub != null) {
                    pub.recordDropFromPolicy();
                }
            }
            executor.execute(r);
        }
    }

    private AutomationRunStreamPublisher resolvePublisher() {
        if (cachedPublisher == null) {
            try {
                cachedPublisher = applicationContext.getBean(AutomationRunStreamPublisher.class);
            } catch (Exception ignored) {
                // Bean not yet available during startup — drop silently;
                // counter loss during boot is acceptable.
            }
        }
        return cachedPublisher;
    }
}
