package com.auraboot.framework.eventpolicy;

import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.eventpolicy.entity.DrtPolicyVersionEntity;
import com.auraboot.framework.eventpolicy.executor.ActionHandler;
import com.auraboot.framework.eventpolicy.executor.ActionExecutionStatus;
import com.auraboot.framework.eventpolicy.executor.PolicyExecutionResult;
import com.auraboot.framework.eventpolicy.model.ConflictStrategy;
import com.auraboot.framework.eventpolicy.model.DedupStrategy;
import com.auraboot.framework.eventpolicy.model.EventPolicyExecutionResult;
import com.auraboot.framework.eventpolicy.model.EventPolicyResult;
import com.auraboot.framework.eventpolicy.model.ExecutionMode;
import com.auraboot.framework.eventpolicy.model.FailureStrategy;
import com.auraboot.framework.eventpolicy.model.MatchMode;
import com.auraboot.framework.eventpolicy.model.PolicyPhase;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;
import com.auraboot.framework.eventpolicy.service.EventPolicyDefinitionService;
import com.auraboot.framework.eventpolicy.service.EventPolicyRuntimeService;
import com.auraboot.framework.eventpolicy.service.EventPolicyVersionService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end EventPolicy run-AND-execute over the real stack (docs/2.md §2): event → matched rules →
 * resolved action plans → PolicyExecutor dispatches to a registered handler → idempotency logged.
 * A {@link TestConfiguration} registers a recording NOTIFY handler so the executor has something to
 * dispatch to (production wires domain handlers — a later slice).
 */
@Import(EventPolicyRunExecuteIntegrationTest.TestHandlers.class)
class EventPolicyRunExecuteIntegrationTest extends BaseIntegrationTest {

    static final AtomicInteger NOTIFY_INVOCATIONS = new AtomicInteger();

    @TestConfiguration
    static class TestHandlers {
        @Bean
        ActionHandler notifyTestHandler() {
            return new ActionHandler() {
                // test-specific action type so it doesn't shadow the production NotifyActionHandler (NOTIFY)
                @Override public boolean supports(String type) { return "TEST_NOTIFY".equals(type); }
                @Override public void execute(ResolvedActionPlan plan, DecisionContext ctx) {
                    NOTIFY_INVOCATIONS.incrementAndGet();
                }
            };
        }
    }

    @Autowired private EventPolicyDefinitionService definitionService;
    @Autowired private EventPolicyVersionService versionService;
    @Autowired private EventPolicyRuntimeService runtimeService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ObjectMapper mapper = new ObjectMapper();

    private String publishNotifyPolicy(String code) throws Exception {
        definitionService.create(code, "Run+Exec IT", "FORM_SUBMITTED", "FORM", "complaint");

        String rules = """
            [{"ruleCode":"R-NOTIFY","ruleName":"notify high","priority":100,"enabled":true,
              "condition":{"type":"compare",
                 "left":{"type":"path","scope":"record","path":"data.priority","dataType":"enum"},
                 "operator":"EQ","right":{"type":"literal","value":"HIGH","dataType":"enum"}},
              "actions":[{"type":"TEST_NOTIFY","target":"ROLE:mgr","order":10,"payload":{},
                 "idempotencyKeyTemplate":"${record.entityCode}:${record.recordId}:${rule.ruleCode}:NOTIFY"}]}]
            """;
        JsonNode rulesJson = mapper.readTree(rules);
        DrtPolicyVersionEntity draft = versionService.createDraft(
                code, PolicyPhase.AFTER_COMMIT, MatchMode.COLLECT_ALL, ExecutionMode.ORDERED,
                FailureStrategy.CONTINUE_ON_ERROR, ConflictStrategy.REJECT_ON_CONFLICT,
                DedupStrategy.BY_IDEMPOTENCY_KEY, rulesJson);
        versionService.validate(draft.getPid());
        versionService.publish(draft.getPid());
        return code;
    }

    private Map<String, Map<String, Object>> ctx(String priority) {
        return Map.of("record", Map.of("entityCode", "complaint", "recordId", "CMP-RX-1",
                "data", Map.of("priority", priority)));
    }

    @Test
    void runAndExecute_dispatchesToHandler_andLogsIdempotency() throws Exception {
        int before = NOTIFY_INVOCATIONS.get();
        String code = "it_runexec_" + System.nanoTime();
        publishNotifyPolicy(code);

        EventPolicyExecutionResult r = runtimeService.runAndExecute("FORM_SUBMITTED", "FORM", "complaint", ctx("HIGH"));

        // decision half matched + resolved one NOTIFY plan
        assertThat(r.policy().status()).isEqualTo(EventPolicyResult.Status.MATCHED);
        assertThat(r.policy().actionPlans()).hasSize(1);
        // execution half: handler invoked, success, idempotency row written
        assertThat(r.execution().overallStatus()).isEqualTo(PolicyExecutionResult.OverallStatus.ALL_SUCCESS);
        assertThat(r.execution().actions().get(0).status()).isEqualTo(ActionExecutionStatus.SUCCESS);
        assertThat(NOTIFY_INVOCATIONS.get()).isEqualTo(before + 1);

        String key = r.execution().actions().get(0).idempotencyKey();
        Integer rows = jdbcTemplate.queryForObject(
                "select count(*) from ab_drt_policy_exec_log where tenant_id = ? and idempotency_key = ? and status = 'SUCCESS'",
                Integer.class, getTestTenant().getId(), key);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void runAndExecute_noMatch_nothingToDo() throws Exception {
        String code = "it_runexec_nm_" + System.nanoTime();
        publishNotifyPolicy(code);
        EventPolicyExecutionResult r = runtimeService.runAndExecute("FORM_SUBMITTED", "FORM", "complaint", ctx("LOW"));
        assertThat(r.policy().status()).isEqualTo(EventPolicyResult.Status.NOT_MATCHED);
        assertThat(r.execution().overallStatus()).isEqualTo(PolicyExecutionResult.OverallStatus.NOTHING_TO_DO);
    }
}
