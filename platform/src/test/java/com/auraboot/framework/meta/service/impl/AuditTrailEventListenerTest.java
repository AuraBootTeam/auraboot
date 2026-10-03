package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.AuditTrailEvent;
import com.auraboot.module.meta.event.CommandCompletedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.auraboot.framework.application.tenant.MetaContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditTrailEventListenerTest {

    @Mock
    private AuditTrailService auditTrailService;

    @AfterEach
    void clearContext() { MetaContext.clear(); }

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

    @Test
    void asyncAuditBindsEventTenantAndActorWithoutInheritedAuthority() {
        List<MetaContext.Snapshot> observed = captureContexts(false);
        listener().onCommandCompleted(event(11L, 17L));
        assertThat(observed).hasSize(1);
        assertThat(observed.getFirst()).isNotNull();
        assertThat(observed.getFirst().tenantId()).isEqualTo(11L);
        assertThat(observed.getFirst().userId()).isEqualTo(17L);
        assertThat(observed.getFirst().roleIds()).isEmpty();
        assertThat(MetaContext.exists()).isFalse();
    }

    @Test
    void foregroundIdentityAndScopeAreRestoredAfterSuccess() {
        MetaContext.setContext(99L, 77L, "caller", "Caller", Set.of(123L));
        MetaContext.setEnvironmentId(1234L);
        MetaContext.Snapshot caller = MetaContext.snapshot();
        List<MetaContext.Snapshot> observed = captureContexts(false);
        listener().onCommandCompleted(event(11L, 17L));
        assertThat(observed.getFirst().tenantId()).isEqualTo(11L);
        assertThat(observed.getFirst().envId()).isNull();
        assertThat(observed.getFirst().roleIds()).isEmpty();
        assertThat(MetaContext.snapshot()).isEqualTo(caller);
    }

    @Test
    void auditFailureRestoresForegroundIdentity() {
        MetaContext.setContext(99L, 77L, "caller", "Caller");
        MetaContext.Snapshot caller = MetaContext.snapshot();
        List<MetaContext.Snapshot> observed = captureContexts(true);
        listener().onCommandCompleted(event(11L, 17L));
        assertThat(observed.getFirst().tenantId()).isEqualTo(11L);
        assertThat(MetaContext.snapshot()).isEqualTo(caller);
    }

    @Test
    void successiveAsyncEventsDoNotLeakTenantOrActor() {
        List<MetaContext.Snapshot> observed = captureContexts(false);
        listener().onCommandCompleted(event(11L, 17L));
        assertThat(MetaContext.exists()).isFalse();
        listener().onCommandCompleted(event(22L, null));
        assertThat(observed).hasSize(2);
        assertThat(observed.get(0).tenantId()).isEqualTo(11L);
        assertThat(observed.get(1).tenantId()).isEqualTo(22L);
        assertThat(observed.get(1).userId()).isZero();
        assertThat(MetaContext.exists()).isFalse();
    }

    @Test
    void missingEventTenantCannotWriteUsingForegroundTenant() {
        MetaContext.setContext(99L, 77L, "caller", "Caller");
        MetaContext.Snapshot caller = MetaContext.snapshot();
        listener().onCommandCompleted(event(null, 17L));
        verifyNoInteractions(auditTrailService);
        assertThat(MetaContext.snapshot()).isEqualTo(caller);
    }

    private AuditTrailEventListener listener() {
        return new AuditTrailEventListener(auditTrailService, new ObjectMapper());
    }

    private CommandCompletedEvent event(Long tenantId, Long actorId) {
        CommandCompletedEvent event = new CommandCompletedEvent(
                tenantId, "record-pid", "audit_fixture", Map.of("status", "approved"),
                "fixture:approve", "UPDATE");
        if (actorId != null) event.addMetadata("actorId", actorId);
        return event;
    }

    private List<MetaContext.Snapshot> captureContexts(boolean fail) {
        List<MetaContext.Snapshot> observed = new ArrayList<>();
        doAnswer(invocation -> {
            observed.add(MetaContext.snapshot());
            if (fail) throw new IllegalStateException("audit storage unavailable");
            return null;
        }).when(auditTrailService).recordAudit(any());
        return observed;
    }
}
