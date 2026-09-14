package com.auraboot.framework.webhook.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.webhook.entity.WebhookDeliveryLog;
import com.auraboot.framework.webhook.mapper.WebhookDeliveryLogMapper;
import com.auraboot.framework.webhook.service.WebhookDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookDeliveryWorker {
    private static final int BATCH_SIZE = 50;
    private static final long LEASE_SECONDS = 30;

    private final WebhookDeliveryLogMapper deliveryMapper;
    private final WebhookDispatcher dispatcher;

    @Scheduled(fixedDelayString = "${webhook.delivery.poll-interval-ms:1000}")
    public void poll() {
        String leaseToken = UUID.randomUUID().toString();
        String worker = ManagementFactory.getRuntimeMXBean().getName();
        for (WebhookDeliveryLog delivery : deliveryMapper.claimReady(
                BATCH_SIZE, worker, leaseToken, Instant.now().plusSeconds(LEASE_SECONDS))) {
            MetaContext.Snapshot previous = MetaContext.snapshot();
            try {
                MetaContext.setContext(delivery.getTenantId(), 0L,
                        "webhook-delivery", "webhook-delivery");
                dispatcher.processClaimed(delivery);
            } catch (Exception exception) {
                log.error("Webhook delivery worker failed outside attempt handling: delivery={}",
                        delivery.getPid(), exception);
            } finally {
                MetaContext.clear();
                MetaContext.restore(previous);
            }
        }
    }
}
