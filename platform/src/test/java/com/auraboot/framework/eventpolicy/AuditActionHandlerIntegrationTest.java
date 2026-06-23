package com.auraboot.framework.eventpolicy;

import com.auraboot.framework.eventpolicy.model.ConflictStrategy;
import com.auraboot.framework.eventpolicy.model.DedupStrategy;
import com.auraboot.framework.eventpolicy.model.EventPolicyExecutionResult;
import com.auraboot.framework.eventpolicy.model.ExecutionMode;
import com.auraboot.framework.eventpolicy.model.FailureStrategy;
import com.auraboot.framework.eventpolicy.model.MatchMode;
import com.auraboot.framework.eventpolicy.model.PolicyPhase;
import com.auraboot.framework.eventpolicy.service.EventPolicyDefinitionService;
import com.auraboot.framework.eventpolicy.service.EventPolicyRuntimeService;
import com.auraboot.framework.eventpolicy.service.EventPolicyVersionService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WRITE_AUDIT action over the real stack: the production {@code AuditActionHandler} (@Component, no
 * test stub) is dispatched by the executor and writes an {@code ab_drt_action_audit} row. Proves the
 * ActionHandler SPI end-to-end with a real registered handler (docs/2.md §7).
 */
class AuditActionHandlerIntegrationTest extends BaseIntegrationTest {

    @Autowired private EventPolicyDefinitionService definitionService;
    @Autowired private EventPolicyVersionService versionService;
    @Autowired private EventPolicyRuntimeService runtimeService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void writeAuditAction_writesAuditRow_viaRealHandler() throws Exception {
        String code = "it_audit_" + System.nanoTime();
        String targetKey = code + "_form";
        definitionService.create(code, "Audit IT", "FORM_SUBMITTED", "FORM", targetKey);
        JsonNode rules = mapper.readTree("""
            [{"ruleCode":"R-AUD","ruleName":"audit high","priority":100,"enabled":true,
              "condition":{"type":"compare",
                 "left":{"type":"path","scope":"record","path":"data.priority","dataType":"enum"},
                 "operator":"EQ","right":{"type":"literal","value":"HIGH","dataType":"enum"}},
              "actions":[{"type":"WRITE_AUDIT","target":"AUDIT:%s","order":10,
                 "payload":{"message":"high-priority complaint received"},
                 "idempotencyKeyTemplate":"${record.entityCode}:${record.recordId}:${rule.ruleCode}:AUDIT"}]}]
            """.formatted(targetKey));
        var draft = versionService.createDraft(code, PolicyPhase.AFTER_COMMIT, MatchMode.COLLECT_ALL,
                ExecutionMode.ORDERED, FailureStrategy.CONTINUE_ON_ERROR, ConflictStrategy.REJECT_ON_CONFLICT,
                DedupStrategy.BY_IDEMPOTENCY_KEY, rules);
        versionService.validate(draft.getPid());
        versionService.publish(draft.getPid());

        Long tid = getTestTenant().getId();
        String recordId = "CMP-AUD-" + System.nanoTime();
        EventPolicyExecutionResult result = runtimeService.runAndExecute("FORM_SUBMITTED", "FORM", targetKey,
                Map.of("record", Map.of("entityCode", targetKey, "recordId", recordId,
                        "data", Map.of("priority", "HIGH"))));

        // the policy matched and the WRITE_AUDIT action executed successfully (real handler)
        assertThat(result.policy().status().name()).isEqualTo("MATCHED");
        assertThat(result.execution().actions()).hasSize(1);
        assertThat(result.execution().actions().get(0).status().name()).isEqualTo("SUCCESS");

        // a real audit row was written by the production handler
        Map<String, Object> audit = jdbcTemplate.queryForMap(
                "select rule_code, action_type, target, message from ab_drt_action_audit "
                        + "where tenant_id=? and idempotency_key=?",
                tid, targetKey + ":" + recordId + ":R-AUD:AUDIT");
        assertThat(audit.get("rule_code")).isEqualTo("R-AUD");
        assertThat(audit.get("action_type")).isEqualTo("WRITE_AUDIT");
        assertThat(audit.get("target")).isEqualTo("AUDIT:" + targetKey);
        assertThat(audit.get("message")).isEqualTo("high-priority complaint received");
    }
}
