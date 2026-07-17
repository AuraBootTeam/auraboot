package com.auraboot.framework.permission.service;

import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.permission.entity.PermissionAuditLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link PermissionAuditRecordPidResolver} (deep-review DR-20260701 R5-A4 test gap).
 * Resolves an audit row's internal numeric id to its public {@code pid}; covers the happy path plus
 * the three short-circuit branches (missing id, unknown model, no matching row).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PermissionAuditRecordPidResolverTest {

    @Mock
    private MetaModelService metaModelService;
    @Mock
    private DynamicDataMapper dynamicDataMapper;

    @InjectMocks
    private PermissionAuditRecordPidResolver resolver;

    private PermissionAuditLog auditLog(Long recordId, String resourceCode, Long tenantId) {
        PermissionAuditLog log = new PermissionAuditLog();
        log.setRecordId(recordId);
        log.setResourceCode(resourceCode);
        log.setTenantId(tenantId);
        return log;
    }

    private MetaModelDTO model(String code, String tableName) {
        return MetaModelDTO.builder().code(code).tableName(tableName).build();
    }

    @Test
    @DisplayName("resolves the public pid when the audit row maps to a live record")
    void resolvesPidForLiveRecord() {
        when(metaModelService.findByCode("crm_lead")).thenReturn(model("crm_lead", "mt_crm_lead"));
        Map<String, Object> row = new HashMap<>();
        row.put("pid", "P123");
        when(dynamicDataMapper.selectByQuery(anyString(), any())).thenReturn(List.of(row));

        assertThat(resolver.resolve(auditLog(5L, "crm_lead", 1L))).isEqualTo("P123");
    }

    @Test
    @DisplayName("returns the persisted public pid without resolving internal id")
    void returnsPersistedRecordPidFirst() {
        PermissionAuditLog log = auditLog(null, "crm_lead", 1L);
        log.setRecordPid("PERSISTED-PID-1");

        assertThat(resolver.resolve(log)).isEqualTo("PERSISTED-PID-1");
    }

    @Test
    @DisplayName("returns null when the audit row has no internal record id")
    void returnsNullWhenRecordIdMissing() {
        assertThat(resolver.resolve(auditLog(null, "crm_lead", 1L))).isNull();
    }

    @Test
    @DisplayName("returns null when the resource model is unknown")
    void returnsNullWhenModelUnknown() {
        when(metaModelService.findByCode("ghost")).thenReturn(null);
        assertThat(resolver.resolve(auditLog(5L, "ghost", 1L))).isNull();
    }

    @Test
    @DisplayName("returns null when no row matches the internal id")
    void returnsNullWhenNoRowMatches() {
        when(metaModelService.findByCode("crm_lead")).thenReturn(model("crm_lead", "mt_crm_lead"));
        when(dynamicDataMapper.selectByQuery(anyString(), any())).thenReturn(List.of());

        assertThat(resolver.resolve(auditLog(5L, "crm_lead", 1L))).isNull();
    }
}
