package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.AuditTrailEvent;
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

@ExtendWith(MockitoExtension.class)
class AuditTrailEventListenerTest {

    @Mock
    private AuditTrailService auditTrailService;

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
