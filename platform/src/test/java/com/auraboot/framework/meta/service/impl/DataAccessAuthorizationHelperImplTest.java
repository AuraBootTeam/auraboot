package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.service.DataAccessAuthorizationContext;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataAccessAuthorizationHelperImplTest {

    private static final Long TENANT_ID = 10L;
    private static final Long USER_ID = 20L;
    private static final String RESOURCE_CODE = "quote_order";

    @Mock
    private DataPermissionEngine dataPermissionEngine;

    private DataAccessAuthorizationHelperImpl helper;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(TENANT_ID, USER_ID, "user-pid", "tester");
        MetaContext.setMemberId(30L);
        helper = new DataAccessAuthorizationHelperImpl(dataPermissionEngine);
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("authorizeList returns normalized explicit-action DataScope filter")
    void authorizeList_returnsNormalizedFilter() {
        when(dataPermissionEngine.buildRowFilter(TENANT_ID, RESOURCE_CODE, "read", USER_ID))
                .thenReturn("AND created_by = 20");

        DataAccessAuthorizationContext context = helper.authorizeList(RESOURCE_CODE, "read");

        assertThat(context.tenantId()).isEqualTo(TENANT_ID);
        assertThat(context.userId()).isEqualTo(USER_ID);
        assertThat(context.resourceCode()).isEqualTo(RESOURCE_CODE);
        assertThat(context.actionCode()).isEqualTo("read");
        assertThat(context.filterClause()).isEqualTo("created_by = 20");
        assertThat(context.asWhereConjunction()).isEqualTo("AND created_by = 20");
    }

    @Test
    @DisplayName("authorizeList fails closed when the permission engine errors")
    void authorizeList_failsClosedOnEngineError() {
        when(dataPermissionEngine.buildRowFilter(TENANT_ID, RESOURCE_CODE, "read", USER_ID))
                .thenThrow(new IllegalStateException("scope unavailable"));

        assertThatThrownBy(() -> helper.authorizeList(RESOURCE_CODE, "read"))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("Data permission evaluation failed");
    }

    @Test
    @DisplayName("authorizeRecord delegates action-aware record access and allows true verdict")
    void authorizeRecord_allowsTrueVerdict() {
        Map<String, Object> record = Map.of("pid", "q-1", "created_by", USER_ID);
        when(dataPermissionEngine.canAccessRecord(TENANT_ID, RESOURCE_CODE, "delete", USER_ID, record))
                .thenReturn(true);

        assertThat(helper.authorizeRecord(RESOURCE_CODE, "delete", record)).isTrue();
        verify(dataPermissionEngine).canAccessRecord(TENANT_ID, RESOURCE_CODE, "delete", USER_ID, record);
    }

    @Test
    @DisplayName("authorizeRecord throws on denied record")
    void authorizeRecord_throwsOnDeniedRecord() {
        Map<String, Object> record = Map.of("pid", "q-2", "created_by", 999L);
        when(dataPermissionEngine.canAccessRecord(TENANT_ID, RESOURCE_CODE, "delete", USER_ID, record))
                .thenReturn(false);

        assertThatThrownBy(() -> helper.authorizeRecord(RESOURCE_CODE, "delete", record))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("Access denied");
    }

    @Test
    @DisplayName("authorizeRecordId loads via caller function and applies record authorization")
    void authorizeRecordId_usesCallerLoader() {
        Map<String, Object> record = Map.of("pid", "q-3", "created_by", USER_ID);
        when(dataPermissionEngine.canAccessRecord(TENANT_ID, RESOURCE_CODE, "read", USER_ID, record))
                .thenReturn(true);

        assertThat(helper.authorizeRecordId(RESOURCE_CODE, "read", "q-3", id -> record)).isTrue();
    }

    @Test
    @DisplayName("authorizeRecordId denies missing record")
    void authorizeRecordId_deniesMissingRecord() {
        assertThatThrownBy(() -> helper.authorizeRecordId(RESOURCE_CODE, "read", "missing", id -> null))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("Access denied");
    }
}
