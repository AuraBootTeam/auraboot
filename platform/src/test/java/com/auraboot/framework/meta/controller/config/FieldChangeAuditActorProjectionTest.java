package com.auraboot.framework.meta.controller.config;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.entity.FieldChangeLog;
import com.auraboot.framework.meta.service.impl.FieldChangeAuditService;
import com.auraboot.framework.user.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class FieldChangeAuditActorProjectionTest {
    @Mock FieldChangeAuditService audit;
    @Mock UserMapper users;
    @InjectMocks FieldChangeAuditController controller;
    private AutoCloseable mocks;
    private static final String ACTOR_PID = "01M3ZM167EJDXYZH12XHBSXMP2";

    @BeforeEach void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        MetaContext.setContext(41L, 7L, ACTOR_PID, ACTOR_PID);
    }
    @AfterEach void tearDown() throws Exception {
        MetaContext.clear();
        mocks.close();
    }
    private FieldChangeLog change(Long actorId, String actorName) {
        FieldChangeLog change = new FieldChangeLog();
        change.setActorId(actorId);
        change.setActorName(actorName);
        change.setFieldCode("inv_lot_expiry_date");
        change.setFieldLabel("有效期至");
        change.setOldValue("2027-09-01");
        change.setNewValue("2028-09-01");
        return change;
    }
    @Test void resolvesDistinctActorsInTheCurrentTenantWithoutMutatingStoredAudit() {
        FieldChangeLog stored = change(7L, ACTOR_PID);
        when(audit.getRecordHistoryByRecordPid(41L, "inv_lot", "lot-pid"))
                .thenReturn(List.of(stored, stored));
        when(users.findDisplayNamesByIdsInTenant(41L, List.of(7L)))
                .thenReturn(List.of(Map.of("id", 7L, "display_name", "仓库管理员")));
        var response = controller.getRecordChanges("inv_lot", "lot-pid").getData();
        assertThat(response).hasSize(2).allSatisfy(row -> {
            assertThat(row.actorName()).isEqualTo("仓库管理员");
            assertThat(row.oldValue()).isEqualTo("2027-09-01");
            assertThat(row.newValue()).isEqualTo("2028-09-01");
        });
        verify(users, times(1)).findDisplayNamesByIdsInTenant(41L, List.of(7L));
        assertThat(stored.getActorName()).isEqualTo(ACTOR_PID);
    }
    @Test void neverFallsBackToAnUnresolvedUserPidOrQueriesAnUnscopedDirectory() {
        when(audit.getRecordHistoryByRecordPid(41L, "inv_lot", "lot-pid"))
                .thenReturn(List.of(change(999L, ACTOR_PID), change(0L, ACTOR_PID), change(0L, "System")));
        when(users.findDisplayNamesByIdsInTenant(41L, List.of(999L))).thenReturn(List.of());
        var response = controller.getRecordChanges("inv_lot", "lot-pid").getData();
        assertThat(response.get(0).actorName()).isNull();
        assertThat(response.get(1).actorName()).isNull();
        assertThat(response.get(2).actorName()).isEqualTo("System");
        verify(users).findDisplayNamesByIdsInTenant(41L, List.of(999L));
        verifyNoMoreInteractions(users);
    }
}
