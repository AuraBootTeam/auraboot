package com.auraboot.framework.notification.wechat;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.extension.BackgroundTenantAccessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Disabled/readiness-gated tenant scan. It never reclaims an attempted send for another HTTP attempt. */
@Component
@RequiredArgsConstructor
@Slf4j
public class WechatSubscriptionDeliveryWorker {
    private final WechatSubscriptionDeliveryJournal journal;
    private final BackgroundTenantAccessor tenants;
    @Scheduled(fixedDelayString="${aura.notification.wechat-subscription.poll-ms:15000}")
    public void tick() {
        if(!journal.readyForDispatch())return;
        for(Long tenant:tenants.listActiveTenantIds()) {
            MetaContext.Snapshot previous=MetaContext.snapshot();
            try {
                MetaContext.setContext(tenant,0L,"notification-runtime","notification-runtime");
                journal.dispatchPending(tenant,20);
            } catch(RuntimeException failure) {
                // Scheduler boundary: one failed tenant does not change another tenant's attempt fences.
                log.error("Notification intent dispatch failed for tenant {}",tenant,failure);
            } finally {MetaContext.clear();MetaContext.restore(previous);}
        }
    }
}
