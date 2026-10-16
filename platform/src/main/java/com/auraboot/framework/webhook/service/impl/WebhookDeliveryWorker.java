package com.auraboot.framework.webhook.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.webhook.entity.WebhookDeliveryLog;
import com.auraboot.framework.webhook.mapper.WebhookDeliveryLogMapper;
import com.auraboot.framework.webhook.service.WebhookDispatcher;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookDeliveryWorker {
    private static final int CONCURRENCY = 8;
    private static final long LEASE_SECONDS = 90;
    private final WebhookDeliveryLogMapper deliveryMapper;
    private final WebhookDispatcher dispatcher;
    private final Semaphore available = new Semaphore(CONCURRENCY);
    private final ThreadPoolExecutor senders = new ThreadPoolExecutor(CONCURRENCY, CONCURRENCY,
            0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(CONCURRENCY),
            Thread.ofPlatform().inheritInheritableThreadLocals(false).name("webhook-delivery-", 0).factory());
    private volatile boolean closing;

    @Scheduled(fixedDelayString = "${webhook.delivery.poll-interval-ms:1000}")
    public synchronized void poll() {
        if (closing) return;
        int capacity = available.availablePermits();
        if (capacity == 0 || !available.tryAcquire(capacity)) return;
        String token = UUID.randomUUID().toString();
        java.util.List<WebhookDeliveryLog> claimed;
        try {
            claimed = deliveryMapper.claimReady(capacity, ManagementFactory.getRuntimeMXBean().getName(),
                    token, Instant.now().plusSeconds(LEASE_SECONDS));
            deliveryMapper.releaseUnusedReceivers(token);
        } catch (RuntimeException error) {
            available.release(capacity);
            throw error;
        }
        available.release(capacity - claimed.size());
        for (WebhookDeliveryLog delivery : claimed) {
            // Slots include the bounded handoff queue; no large preclaimed batch waits behind a slow send.
            senders.execute(() -> deliver(delivery));
        }
    }

    private void deliver(WebhookDeliveryLog delivery) {
        MetaContext.Snapshot previous = MetaContext.snapshot();
        try {
            MetaContext.setContext(delivery.getTenantId(), 0L, "webhook-delivery", "webhook-delivery");
            if (delivery.getRequestId() != null) MDC.put("requestId", delivery.getRequestId());
            dispatcher.processClaimed(delivery);
        } catch (Exception error) {
            // Per-attempt tolerance: persistence failures retain the fenced lease for recovery.
            log.error("Webhook delivery worker failed outside attempt handling: delivery={}", delivery.getPid(), error);
        } finally {
            MDC.remove("requestId");
            MetaContext.clear();
            MetaContext.restore(previous);
            available.release();
        }
        // Drain healthy receivers immediately; scheduled polling remains the arrival/recovery trigger.
        poll();
    }

    @PreDestroy
    public synchronized void close() {
        closing = true;
        senders.shutdownNow();
    }
}
