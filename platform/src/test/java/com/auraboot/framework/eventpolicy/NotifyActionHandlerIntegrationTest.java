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
 * NOTIFY action over the real stack: the production {@code NotifyActionHandler} (@Component) is
 * dispatched by the executor and sends an in-app notification through the real
 * {@code NotificationService}, persisting an {@code ab_notification} row. Proves the EventPolicy
 * executor wires to a real platform subsystem (docs/2.md §7).
 */
class NotifyActionHandlerIntegrationTest extends BaseIntegrationTest {

    @Autowired private EventPolicyDefinitionService definitionService;
    @Autowired private EventPolicyVersionService versionService;
    @Autowired private EventPolicyRuntimeService runtimeService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void notifyAction_persistsInAppNotification_viaRealNotificationService() throws Exception {
        long userId = 880000L + (System.nanoTime() % 100000);
        String code = "it_notify_" + System.nanoTime();
        String targetKey = code + "_form";
        definitionService.create(code, "Notify IT", "FORM_SUBMITTED", "FORM", targetKey);
        JsonNode rules = mapper.readTree("""
            [{"ruleCode":"R-NOTIFY","ruleName":"notify high","priority":100,"enabled":true,
              "condition":{"type":"compare",
                 "left":{"type":"path","scope":"record","path":"data.priority","dataType":"enum"},
                 "operator":"EQ","right":{"type":"literal","value":"HIGH","dataType":"enum"}},
              "actions":[{"type":"NOTIFY","target":"USER:%d","order":10,
                 "payload":{"title":"High priority complaint","content":"please review"},
                 "idempotencyKeyTemplate":"${record.entityCode}:${record.recordId}:${rule.ruleCode}:NOTIFY"}]}]
            """.formatted(userId));
        var draft = versionService.createDraft(code, PolicyPhase.AFTER_COMMIT, MatchMode.COLLECT_ALL,
                ExecutionMode.ORDERED, FailureStrategy.CONTINUE_ON_ERROR, ConflictStrategy.REJECT_ON_CONFLICT,
                DedupStrategy.BY_IDEMPOTENCY_KEY, rules);
        versionService.validate(draft.getPid());
        versionService.publish(draft.getPid());

        Long tid = getTestTenant().getId();
        EventPolicyExecutionResult result = runtimeService.runAndExecute("FORM_SUBMITTED", "FORM", targetKey,
                Map.of("record", Map.of("entityCode", targetKey, "recordId", "CMP-N-1",
                        "data", Map.of("priority", "HIGH"))));

        assertThat(result.policy().status().name()).isEqualTo("MATCHED");
        assertThat(result.execution().actions().get(0).status().name()).isEqualTo("SUCCESS");

        // a real in-app notification was persisted for the target user by NotificationService
        Map<String, Object> n = jdbcTemplate.queryForMap(
                "select title, content, source_type from ab_notification where tenant_id=? and user_id=?",
                tid, userId);
        assertThat(n.get("title")).isEqualTo("High priority complaint");
        assertThat(n.get("content")).isEqualTo("please review");
        assertThat(n.get("source_type")).isEqualTo("EVENT_POLICY");
    }
}
