package com.auraboot.framework.crm.listener;

import com.auraboot.framework.crm.service.CrmPrimaryContactService;
import com.auraboot.module.meta.event.CommandCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Set;

/**
 * Ensures crm_contact respects the "single primary contact per account" rule
 * after command writes commit successfully.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CrmPrimaryContactListener {

    private static final Set<String> SUPPORTED_OPERATIONS = Set.of("create", "update");

    private final CrmPrimaryContactService crmPrimaryContactService;

    @Async("eventTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommandCompleted(CommandCompletedEvent event) {
        if (!"crm_contact".equals(event.getModelCode())) {
            return;
        }
        if (!SUPPORTED_OPERATIONS.contains(event.getOperationType())) {
            return;
        }

        try {
            crmPrimaryContactService.ensureSinglePrimaryContact(event.getTenantId(), event.getRecordId());
        } catch (Exception e) {
            log.error("Failed to normalize CRM primary contact for tenant={}, contactPid={}, command={}: {}",
                    event.getTenantId(), event.getRecordId(), event.getCommandCode(), e.getMessage(), e);
        }
    }
}
