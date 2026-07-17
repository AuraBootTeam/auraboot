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

import java.util.List;
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
                 "idempotencyKeyTemplate":"${record.entityCode}:${record.recordPid}:${rule.ruleCode}:NOTIFY"}]}]
            """.formatted(userId));
        var draft = versionService.createDraft(code, PolicyPhase.AFTER_COMMIT, MatchMode.COLLECT_ALL,
                ExecutionMode.ORDERED, FailureStrategy.CONTINUE_ON_ERROR, ConflictStrategy.REJECT_ON_CONFLICT,
                DedupStrategy.BY_IDEMPOTENCY_KEY, rules);
        versionService.validate(draft.getPid());
        versionService.publish(draft.getPid());

        Long tid = getTestTenant().getId();
        EventPolicyExecutionResult result = runtimeService.runAndExecute("FORM_SUBMITTED", "FORM", targetKey,
                Map.of("record", Map.of("entityCode", targetKey, "recordPid", "CMP-N-1",
                        "data", Map.of("priority", "HIGH"))));

        assertThat(result.policy().status().name()).isEqualTo("MATCHED");
        assertThat(result.execution().actions().get(0).status().name()).isEqualTo("SUCCESS");
        assertThat(result.execution().actions().get(0).resultPayload())
                .containsEntry("channel", "in_app")
                .containsEntry("recipientType", "USER")
                .containsEntry("recipientId", String.valueOf(userId))
                .containsEntry("sentCount", 1);

        // a real in-app notification was persisted for the target user by NotificationService
        Map<String, Object> n = jdbcTemplate.queryForMap(
                "select title, content, source_type from ab_notification where tenant_id=? and user_id=?",
                tid, userId);
        assertThat(n.get("title")).isEqualTo("High priority complaint");
        assertThat(n.get("content")).isEqualTo("please review");
        assertThat(n.get("source_type")).isEqualTo("EVENT_POLICY");
    }

    @Test
    void notifyAction_roleTargetFansOutToRoleMembers_viaRealNotificationService() throws Exception {
        String code = "it_notify_role_" + System.nanoTime();
        String targetKey = code + "_form";
        String title = "Role notification " + System.nanoTime();
        String roleCode = getTestRole().getCode();
        Long recipientUserId = getTestUser().getId();
        definitionService.create(code, "Notify Role IT", "FORM_SUBMITTED", "FORM", targetKey);
        JsonNode rules = mapper.readTree("""
            [{"ruleCode":"R-NOTIFY-ROLE","ruleName":"notify role high","priority":100,"enabled":true,
              "condition":{"type":"compare",
                 "left":{"type":"path","scope":"record","path":"data.priority","dataType":"enum"},
                 "operator":"EQ","right":{"type":"literal","value":"HIGH","dataType":"enum"}},
              "actions":[{"type":"NOTIFY","target":"ROLE:%s","order":10,
                 "payload":{"title":"%s","content":"role member review"},
                 "idempotencyKeyTemplate":"${record.entityCode}:${record.recordPid}:${rule.ruleCode}:NOTIFY"}]}]
            """.formatted(roleCode, title));
        var draft = versionService.createDraft(code, PolicyPhase.AFTER_COMMIT, MatchMode.COLLECT_ALL,
                ExecutionMode.ORDERED, FailureStrategy.CONTINUE_ON_ERROR, ConflictStrategy.REJECT_ON_CONFLICT,
                DedupStrategy.BY_IDEMPOTENCY_KEY, rules);
        versionService.validate(draft.getPid());
        versionService.publish(draft.getPid());

        Long tid = getTestTenant().getId();
        EventPolicyExecutionResult result = runtimeService.runAndExecute("FORM_SUBMITTED", "FORM", targetKey,
                Map.of("record", Map.of("entityCode", targetKey, "recordPid", "CMP-ROLE-1",
                        "data", Map.of("priority", "HIGH"))));

        assertThat(result.policy().status().name()).isEqualTo("MATCHED");
        assertThat(result.execution().overallStatus().name()).isEqualTo("ALL_SUCCESS");
        assertThat(result.execution().actions().get(0).status().name()).isEqualTo("SUCCESS");
        assertThat(result.execution().actions().get(0).resultPayload())
                .containsEntry("channel", "in_app")
                .containsEntry("recipientType", "ROLE")
                .containsEntry("recipientId", roleCode)
                .containsEntry("sentCount", 1)
                .containsEntry("recipientCount", 1);
        assertThat((List<Long>) result.execution().actions().get(0).resultPayload().get("targetUserIds"))
                .containsExactly(recipientUserId);

        Map<String, Object> n = jdbcTemplate.queryForMap("""
                select title, content, source_type, source_id
                from ab_notification
                where tenant_id=? and user_id=? and title=?
                order by created_at desc
                limit 1
                """, tid, recipientUserId, title);
        assertThat(n.get("title")).isEqualTo(title);
        assertThat(n.get("content")).isEqualTo("role member review");
        assertThat(n.get("source_type")).isEqualTo("EVENT_POLICY");
        assertThat(n.get("source_id")).isEqualTo("R-NOTIFY-ROLE");
    }
}
