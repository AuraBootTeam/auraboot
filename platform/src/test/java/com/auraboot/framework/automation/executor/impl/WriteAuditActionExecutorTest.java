package com.auraboot.framework.automation.executor.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.eventpolicy.entity.DrtActionAuditEntity;
import com.auraboot.framework.eventpolicy.mapper.DrtActionAuditMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WriteAuditActionExecutorTest {

    @Mock
    private DrtActionAuditMapper auditMapper;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private WriteAuditActionExecutor executor;

    @BeforeEach
    void setTenantContext() {
        MetaContext.setContext(100L, 200L, "user-200", "tester");
    }

    @AfterEach
    void clearTenantContext() {
        MetaContext.clear();
    }

    @Test
    void supports_writeAuditOnly() {
        assertThat(executor.supports("write_audit")).isTrue();
        assertThat(executor.supports("add_comment")).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_persistsAutomationAuditAndReturnsAuditPid() {
        JsonNode payloadNode = new ObjectMapper().createObjectNode().put("recordPid", "REQ-1");
        when(objectMapper.valueToTree(any())).thenReturn(payloadNode);
        when(auditMapper.insert(any(DrtActionAuditEntity.class))).thenReturn(1);

        AutomationAction action = AutomationAction.builder()
                .type("write_audit")
                .config(Map.of(
                        "message", "自动化 ${automationPid} 命中",
                        "payload", Map.of("recordPid", "${recordPid}")))
                .build();

        Map<String, Object> result = (Map<String, Object>) executor.execute(action, Map.of(
                "automationPid", "auto-1",
                "recordPid", "REQ-1"));

        assertThat(result)
                .containsEntry("success", true)
                .containsEntry("tenantId", 100L)
                .containsEntry("actionType", "write_audit")
                .containsEntry("message", "自动化 auto-1 命中");
        assertThat(result.get("auditPid")).asString().isNotBlank();

        ArgumentCaptor<DrtActionAuditEntity> captor = ArgumentCaptor.forClass(DrtActionAuditEntity.class);
        verify(auditMapper).insert(captor.capture());
        DrtActionAuditEntity row = captor.getValue();
        assertThat(row.getTenantId()).isEqualTo(100L);
        assertThat(row.getRuleCode()).isEqualTo("auto-1");
        assertThat(row.getActionType()).isEqualTo("write_audit");
        assertThat(row.getMessage()).isEqualTo("自动化 auto-1 命中");
        assertThat(row.getPayloadJson()).isSameAs(payloadNode);
    }

    @Test
    void execute_requiresTenantContext() {
        MetaContext.clear();
        AutomationAction action = AutomationAction.builder()
                .type("write_audit")
                .config(Map.of("message", "audit"))
                .build();

        assertThatThrownBy(() -> executor.execute(action, Map.of("automationPid", "auto-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Tenant context");
    }
}
