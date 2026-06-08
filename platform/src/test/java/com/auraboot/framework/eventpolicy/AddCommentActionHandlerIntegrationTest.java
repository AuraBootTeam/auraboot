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
 * ADD_COMMENT action over the real stack: the production {@code AddCommentActionHandler} (@Component)
 * is dispatched by the executor and adds a record comment via the real {@code RecordCommentService},
 * persisting an {@code ab_record_comment} row attached to the event's record (docs/2.md §7).
 */
class AddCommentActionHandlerIntegrationTest extends BaseIntegrationTest {

    @Autowired private EventPolicyDefinitionService definitionService;
    @Autowired private EventPolicyVersionService versionService;
    @Autowired private EventPolicyRuntimeService runtimeService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void addCommentAction_persistsRecordComment_viaRealService() throws Exception {
        String code = "it_comment_" + System.nanoTime();
        String recordPid = "CMP-CMT-" + System.nanoTime();
        definitionService.create(code, "Comment IT", "FORM_SUBMITTED", "FORM", "complaint");
        JsonNode rules = mapper.readTree("""
            [{"ruleCode":"R-CMT","ruleName":"comment high","priority":100,"enabled":true,
              "condition":{"type":"compare",
                 "left":{"type":"path","scope":"record","path":"data.priority","dataType":"enum"},
                 "operator":"EQ","right":{"type":"literal","value":"HIGH","dataType":"enum"}},
              "actions":[{"type":"ADD_COMMENT","target":"RECORD","order":10,
                 "payload":{"content":"auto: high-priority — please triage"},
                 "idempotencyKeyTemplate":"${record.entityCode}:${record.recordId}:${rule.ruleCode}:CMT"}]}]
            """);
        var draft = versionService.createDraft(code, PolicyPhase.AFTER_COMMIT, MatchMode.COLLECT_ALL,
                ExecutionMode.ORDERED, FailureStrategy.CONTINUE_ON_ERROR, ConflictStrategy.REJECT_ON_CONFLICT,
                DedupStrategy.BY_IDEMPOTENCY_KEY, rules);
        versionService.validate(draft.getPid());
        versionService.publish(draft.getPid());

        Long tid = getTestTenant().getId();
        EventPolicyExecutionResult result = runtimeService.runAndExecute("FORM_SUBMITTED", "FORM", "complaint",
                Map.of("record", Map.of("entityCode", "complaint", "recordId", recordPid,
                        "data", Map.of("priority", "HIGH"))));

        assertThat(result.policy().status().name()).isEqualTo("MATCHED");
        assertThat(result.execution().actions().get(0).status().name()).isEqualTo("SUCCESS");

        // a real record comment was persisted by RecordCommentService
        Map<String, Object> c = jdbcTemplate.queryForMap(
                "select content, model_code from ab_record_comment where tenant_id=? and record_pid=?",
                tid, recordPid);
        assertThat(c.get("content")).isEqualTo("auto: high-priority — please triage");
        assertThat(c.get("model_code")).isEqualTo("complaint");
    }
}
