package com.auraboot.framework.integration;

import com.auraboot.framework.event.context.ThreadContextScopes;
import com.auraboot.framework.meta.service.impl.OutboxWorkerImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Immediate delivery after commit; the existing scheduler recovers missed signals and due retries. */
@Slf4j
@Component
public class ReliableOutboxDispatchListener {
    private final OutboxWorkerImpl worker;
    private final Executor executor;
    private final ThreadContextScopes contexts;
    private final ReliableIntegrationMetrics metrics;

    public ReliableOutboxDispatchListener(OutboxWorkerImpl worker,
            @Qualifier("reliableOutboxDispatchExecutor") Executor executor,
            ThreadContextScopes contexts, ReliableIntegrationMetrics metrics) {
        this.worker = worker;
        this.executor = executor;
        this.contexts = contexts;
        this.metrics = metrics;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReady(ReliableOutboxReady event) {
        try {
            executor.execute(() -> contexts.runCleared(() -> {
                metrics.record("commit_dispatch_started");
                try {
                    worker.pollAndDispatch();
                } catch (RuntimeException failure) {
                    // No delivery success is recorded; durable leases/retries remain recoverable.
                    metrics.record("commit_dispatch_failed");
                    log.error("Committed outbox dispatch failed; scheduled recovery remains active", failure);
                }
            }));
        } catch (RejectedExecutionException saturatedOrStopped) {
            // Never run business handlers on the committing HTTP thread or fail a committed acceptance.
            metrics.record("commit_dispatch_deferred");
            log.debug("Outbox commit signal deferred to queued dispatch or scheduled recovery");
        }
    }
}
