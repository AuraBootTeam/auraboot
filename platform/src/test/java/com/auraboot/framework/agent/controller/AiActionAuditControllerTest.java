package com.auraboot.framework.agent.controller;

import com.auraboot.framework.agent.service.AiActionAuditService;
import com.auraboot.framework.agent.service.AiActionRiskAssessor;
import com.auraboot.framework.application.tenant.MetaContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("AI action audit controller")
class AiActionAuditControllerTest {

    @Mock private AiActionRiskAssessor riskAssessor;
    @Mock private AiActionAuditService auditService;

    private AiActionAuditController controller;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(7L, 11L, "user-11", "auditor");
        controller = new AiActionAuditController(riskAssessor, auditService);
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("record audit accepts targetPid as the pid-first target alias")
    void recordAuditAcceptsTargetPidAlias() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversationId", "conv-1");
        body.put("messageId", "msg-1");
        body.put("actionType", "execute_command");
        body.put("commandCode", "crm.account.update");
        body.put("modelCode", "crm_account");
        body.put("targetPid", "REC-PID-001");
        body.put("riskLevel", "medium");
        body.put("userDecision", "confirmed");
        body.put("executionResult", "success");
        body.put("reasoning", "operator confirmed");
        body.put("payload", Map.of("field", "status"));

        controller.recordAudit(body);

        verify(auditService).record(
                eq(7L),
                eq(11L),
                eq("conv-1"),
                eq("msg-1"),
                eq("execute_command"),
                eq("crm.account.update"),
                eq("crm_account"),
                eq("REC-PID-001"),
                eq("medium"),
                eq("confirmed"),
                eq("success"),
                eq(null),
                eq("operator confirmed"),
                eq(Map.of("field", "status"))
        );
    }
}
