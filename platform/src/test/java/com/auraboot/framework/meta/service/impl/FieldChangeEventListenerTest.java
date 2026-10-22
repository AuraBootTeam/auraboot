package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.module.meta.event.CommandCompletedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FieldChangeEventListenerTest {
    @Mock FieldChangeAuditService auditService;
    @Mock MetaModelService models;
    @Mock DynamicDataMapper data;

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

    @Test
    void eventIdentityOwnsBothSnapshotReadAndAuditWithoutRequestContext() {
        List<MetaContext.Snapshot> observed = captureIdentity(false);
        listener().onCommandCompleted(event(11L, "UPDATE"));
        assertThat(observed).hasSize(2);
        assertThat(observed).allSatisfy(snapshot -> {
            assertThat(snapshot).isNotNull();
            assertThat(snapshot.tenantId()).isEqualTo(11L);
            assertThat(snapshot.userId()).isEqualTo(17L);
            assertThat(snapshot.userPid()).isEqualTo("event-actor");
            assertThat(snapshot.roleIds()).isEmpty();
            assertThat(snapshot.envId()).isNull();
        });
        assertThat(MetaContext.snapshot()).isNull();
        verify(data).selectByQuery(contains("tenant_id = #{params.tenantId}"),
                eq(Map.of("tenantId", 11L, "recordPid", "record-pid")));
        verify(auditService).recordFieldChanges(11L, "fixture", 41L, "record-pid",
                "fixture:update", null, Map.of("id", 41L, "pid", "record-pid", "status", "new"),
                17L, "Event actor");
    }

    @Test
    void pooledIdentityIsNotInheritedAndIsRestoredAfterAuditFailure() {
        MetaContext.setContext(99L, 77L, "previous-actor", "Previous", Set.of(123L));
        MetaContext.setEnvironmentId(1234L);
        MetaContext.Snapshot previous = MetaContext.snapshot();
        List<MetaContext.Snapshot> observed = captureIdentity(true);
        listener().onCommandCompleted(event(11L, "UPDATE"));
        assertThat(observed).hasSize(2);
        assertThat(observed).allSatisfy(snapshot -> {
            assertThat(snapshot.tenantId()).isEqualTo(11L);
            assertThat(snapshot.userId()).isEqualTo(17L);
            assertThat(snapshot.roleIds()).isEmpty();
            assertThat(snapshot.envId()).isNull();
        });
        assertThat(MetaContext.snapshot()).isEqualTo(previous);
    }

    @Test
    void missingTenantCannotBorrowForegroundIdentity() {
        MetaContext.setContext(99L, 77L, "caller", "Caller");
        MetaContext.Snapshot previous = MetaContext.snapshot();
        listener().onCommandCompleted(event(null, "DELETE"));
        verifyNoInteractions(models, data, auditService);
        assertThat(MetaContext.snapshot()).isEqualTo(previous);
    }

    @Test
    void successiveDeleteEventsDoNotLeakIdentity() {
        List<MetaContext.Snapshot> observed = new ArrayList<>();
        doAnswer(invocation -> {
            observed.add(MetaContext.snapshot());
            return null;
        }).when(auditService).recordFieldChanges(anyLong(), anyString(), isNull(), anyString(),
                anyString(), isNull(), isNull(), anyLong(), nullable(String.class));
        listener().onCommandCompleted(new CommandCompletedEvent(11L, "record-one", "fixture",
                Map.of(), "fixture:delete", "DELETE"));
        assertThat(MetaContext.snapshot()).isNull();
        listener().onCommandCompleted(new CommandCompletedEvent(22L, "record-two", "fixture",
                Map.of(), "fixture:delete", "DELETE"));
        assertThat(observed).hasSize(2);
        assertThat(observed.get(0)).isNotNull();
        assertThat(observed.get(0).tenantId()).isEqualTo(11L);
        assertThat(observed.get(1).tenantId()).isEqualTo(22L);
        assertThat(observed.get(1).userId()).isZero();
        assertThat(MetaContext.snapshot()).isNull();
        verifyNoInteractions(models, data);
    }

    private List<MetaContext.Snapshot> captureIdentity(boolean fail) {
        List<MetaContext.Snapshot> observed = new ArrayList<>();
        when(models.getTableName("fixture")).thenAnswer(invocation -> {
            observed.add(MetaContext.snapshot());
            return "ab_fixture";
        });
        when(data.selectByQuery(anyString(), anyMap())).thenReturn(
                List.of(Map.of("id", 41L, "pid", "record-pid", "status", "new")));
        doAnswer(invocation -> {
            observed.add(MetaContext.snapshot());
            if (fail) throw new IllegalStateException("audit store unavailable");
            return null;
        }).when(auditService).recordFieldChanges(anyLong(), anyString(), anyLong(), anyString(),
                anyString(), isNull(), anyMap(), anyLong(), anyString());
        return observed;
    }

    private CommandCompletedEvent event(Long tenant, String operation) {
        CommandCompletedEvent event = new CommandCompletedEvent(tenant, "record-pid", "fixture",
                Map.of(), "fixture:update", operation);
        event.addMetadata("actorId", 17L);
        event.addMetadata("actorPid", "event-actor");
        event.addMetadata("actorName", "Event actor");
        return event;
    }

    private FieldChangeEventListener listener() {
        return new FieldChangeEventListener(auditService, models, data);
    }
}
