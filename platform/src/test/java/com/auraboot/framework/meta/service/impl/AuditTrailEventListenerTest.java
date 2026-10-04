package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.AuditTrailEvent;
import com.auraboot.framework.application.tenant.MetaContext;
import org.junit.jupiter.api.AfterEach;
import java.util.concurrent.atomic.AtomicReference;
import com.auraboot.module.meta.event.CommandCompletedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class AuditTrailEventListenerTest {

    @Mock
    private AuditTrailService auditTrailService;

    @AfterEach
    void clearThreadContext() {
        MetaContext.clear();
    }

    @Test
    void asyncAuditBindsEventTenantAndActorWithoutRequestContext() {
        MetaContext.clear();
        AtomicReference<Long> tenant = new AtomicReference<>();
        AtomicReference<Long> actor = new AtomicReference<>();
        when(auditTrailService.recordAudit(any())).thenAnswer(invocation -> {
            tenant.set(MetaContext.getCurrentTenantId());
            actor.set(MetaContext.get().getUserId());
            return null;
        });
        CommandCompletedEvent event = new CommandCompletedEvent(99L, "order-pid", "orders", Map.of(), "order:approve", "UPDATE");
        event.addMetadata("actorId", 10L);
        event.addMetadata("actorName", "Alice");
        new AuditTrailEventListener(auditTrailService, new ObjectMapper()).onCommandCompleted(event);
        assertThat(tenant.get()).isEqualTo(99L);
        assertThat(actor.get()).isEqualTo(10L);
        assertThat(MetaContext.snapshot()).isNull();
    }

    @Test
    void auditFailureRestoresPreviousThreadIdentity() {
        MetaContext.setContext(777L, 55L, "previous-user", "Previous");
        MetaContext.Snapshot previous = MetaContext.snapshot();
        AtomicReference<Long> tenant = new AtomicReference<>();
        when(auditTrailService.recordAudit(any())).thenAnswer(invocation -> {
            tenant.set(MetaContext.getCurrentTenantId());
            throw new IllegalStateException("audit store unavailable");
        });
        CommandCompletedEvent event = new CommandCompletedEvent(99L, "order-pid", "orders", Map.of(), "order:approve", "UPDATE");
        new AuditTrailEventListener(auditTrailService, new ObjectMapper()).onCommandCompleted(event);
        assertThat(tenant.get()).isEqualTo(99L);
        assertThat(MetaContext.snapshot()).isEqualTo(previous);
    }

    @Test
    void commandCompletedWithPidRecordStoresEntityPidAndMetadataAlias() {
        AuditTrailEventListener listener = new AuditTrailEventListener(auditTrailService, new ObjectMapper());
        CommandCompletedEvent event = new CommandCompletedEvent(
                99L,
                "pur_01KPID",
                "mkt_purchase",
                Map.of("status", "approved"),
                "mkt:approve_purchase",
                "UPDATE");
        event.addMetadata("actorId", 10L);
        event.addMetadata("actorName", "Alice");

        listener.onCommandCompleted(event);

        ArgumentCaptor<AuditTrailEvent> captor = ArgumentCaptor.forClass(AuditTrailEvent.class);
        verify(auditTrailService).recordAudit(captor.capture());

        AuditTrailEvent auditEvent = captor.getValue();
        assertThat(auditEvent.getEntityId()).isNull();
        assertThat(auditEvent.getEntityPid()).isEqualTo("pur_01KPID");
        assertThat(auditEvent.getMetadata().get("entityPid").asText()).isEqualTo("pur_01KPID");
    }
}
