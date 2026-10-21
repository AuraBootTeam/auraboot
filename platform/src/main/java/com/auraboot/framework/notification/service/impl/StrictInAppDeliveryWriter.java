package com.auraboot.framework.notification.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.notification.entity.Notification;
import com.auraboot.framework.notification.mapper.NotificationMapper;
import com.auraboot.framework.notification.service.NotificationDeliveryPlanResolver.Plan;
import com.auraboot.framework.notification.service.NotificationSseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Strict single-recipient persistence; the reliable consumer owns the surrounding receipt transaction. */
@Service
@RequiredArgsConstructor
@Slf4j
public class StrictInAppDeliveryWriter {
    private final NotificationMapper mapper;
    private final NotificationSseService sse;

    public long requireRecipient(Plan plan) {
        if (plan == null || plan.tenantId() <= 0 || plan.recipientUserPid() == null || plan.recipientUserPid().isBlank())
            throw new IllegalArgumentException("NOTIFICATION_RECIPIENT_REQUIRED");
        if (!java.util.Objects.equals(MetaContext.getCurrentTenantId(), plan.tenantId()))
            throw new IllegalArgumentException("NOTIFICATION_TENANT_MISMATCH");
        Long id = mapper.findActiveRecipient(plan.tenantId(), plan.recipientUserPid());
        if (id == null) throw new IllegalArgumentException("NOTIFICATION_RECIPIENT_UNAVAILABLE");
        return id;
    }

    public long persist(Plan plan) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("NOTIFICATION_TRANSACTION_REQUIRED");
        if (!java.util.Objects.equals(MetaContext.getCurrentTenantId(), plan.tenantId()))
            throw new IllegalArgumentException("NOTIFICATION_TENANT_MISMATCH");
        long userId = requireRecipient(plan);
        Notification row = new Notification();
        row.setTenantId(plan.tenantId()); row.setUserId(userId);
        row.setTitle(plan.title()); row.setContent(plan.content());
        row.setCategory("business"); row.setPriority("normal"); row.setIsRead(false);
        row.setSourceType(plan.sourceType()); row.setSourceId(plan.sourceId());
        if (mapper.insert(row) != 1 || row.getId() == null)
            throw new IllegalStateException("NOTIFICATION_PERSISTENCE_FAILED");
        MetaContext.Snapshot snapshot = MetaContext.snapshot();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                // Best-effort after-commit transport cannot alter the persistence result or receipt.
                try {
                    MetaContext.runWithSnapshot(snapshot, () -> {
                        if (sse.getActiveConnectionCount(userId) > 0)
                            sse.pushUnreadCount(userId, mapper.countUnread(plan.tenantId(), userId));
                    });
                } catch (RuntimeException e) {
                    log.warn("Notification unread-count push failed after committed persistence", e);
                }
            }
        });
        return row.getId();
    }
}
