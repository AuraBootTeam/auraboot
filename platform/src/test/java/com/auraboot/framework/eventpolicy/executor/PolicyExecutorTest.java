package com.auraboot.framework.eventpolicy.executor;

import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.eventpolicy.model.EventPolicyResult;
import com.auraboot.framework.eventpolicy.model.FailureStrategy;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PolicyExecutor orchestration: ordering, idempotency-skip, failure strategies (docs/2.md §8). */
class PolicyExecutorTest {

    private final Long tenant = 1L;
    private final DecisionContext ctx = DecisionContext.of(Map.of());

    /** A handler for one type that records executions and can be told to fail. */
    static class RecordingHandler implements ActionHandler {
        final String type;
        final boolean fail;
        final List<String> executed;
        final Map<String, Object> resultPayload;
        RecordingHandler(String type, boolean fail, List<String> executed) {
            this(type, fail, executed, Map.of());
        }
        RecordingHandler(String type, boolean fail, List<String> executed, Map<String, Object> resultPayload) {
            this.type = type; this.fail = fail; this.executed = executed; this.resultPayload = resultPayload;
        }
        @Override public boolean supports(String t) { return type.equals(t); }
        @Override public void execute(ResolvedActionPlan plan, DecisionContext c) throws Exception {
            executed.add(plan.idempotencyKey());
            if (fail) throw new IllegalStateException("boom:" + type);
        }
        @Override public Map<String, Object> executeWithResult(ResolvedActionPlan plan, DecisionContext c) throws Exception {
            execute(plan, c);
            return resultPayload;
        }
    }

    static class RecordingStore implements IdempotencyStore {
        final List<ActionExecutionResult> records = new ArrayList<>();

        @Override
        public boolean alreadySucceeded(Long tenantId, String idempotencyKey) {
            return false;
        }

        @Override
        public void record(Long tenantId, String policyCode, ActionExecutionResult result) {
            records.add(result);
        }
    }

    private ResolvedActionPlan plan(String rule, String type, int order, String key) {
        return new ResolvedActionPlan(rule, type, type + ":t", order, Map.of(), key);
    }

    private EventPolicyResult resultWith(ResolvedActionPlan... plans) {
        return new EventPolicyResult("p", EventPolicyResult.Status.MATCHED,
                List.of("R"), List.of(), List.of(plans), List.of());
    }

    @Test
    void allSuccess_inOrder() {
        List<String> exec = new ArrayList<>();
        PolicyExecutor ex = new PolicyExecutor(
                List.of(new RecordingHandler("NOTIFY", false, exec), new RecordingHandler("CREATE_TASK", false, exec)),
                new InMemoryIdempotencyStore());
        PolicyExecutionResult r = ex.execute(
                resultWith(plan("R1", "NOTIFY", 10, "k1"), plan("R2", "CREATE_TASK", 20, "k2")),
                ctx, FailureStrategy.CONTINUE_ON_ERROR, tenant);
        assertThat(r.overallStatus()).isEqualTo(PolicyExecutionResult.OverallStatus.ALL_SUCCESS);
        assertThat(exec).containsExactly("k1", "k2");
    }

    @Test
    void successActionIncludesStructuredHandlerResultPayload() {
        List<String> exec = new ArrayList<>();
        PolicyExecutor ex = new PolicyExecutor(
                List.of(new RecordingHandler("NOTIFY", false, exec,
                        Map.of("recipientType", "USER", "sentCount", 1, "notificationRef", "R1:NOTIFY"))),
                new InMemoryIdempotencyStore());

        PolicyExecutionResult r = ex.execute(
                resultWith(plan("R1", "NOTIFY", 10, "k1")),
                ctx, FailureStrategy.CONTINUE_ON_ERROR, tenant);

        assertThat(r.actions().get(0).status()).isEqualTo(ActionExecutionStatus.SUCCESS);
        assertThat(r.actions().get(0).resultPayload())
                .containsEntry("recipientType", "USER")
                .containsEntry("sentCount", 1)
                .containsEntry("notificationRef", "R1:NOTIFY");
    }

    @Test
    void failedActionCanIncludeStructuredHandlerResultPayloadForTraceEvidence() {
        ActionHandler failingSms = new ActionHandler() {
            @Override
            public boolean supports(String type) {
                return "SEND_SMS".equals(type);
            }

            @Override
            public void execute(ResolvedActionPlan plan, DecisionContext context) {
                throw new UnsupportedOperationException("not used");
            }

            @Override
            public Map<String, Object> executeWithResult(ResolvedActionPlan plan, DecisionContext context) {
                throw new ActionExecutionException("No real SMS sender available", Map.of(
                        "channel", "sms",
                        "targetPhones", List.of("+8613800138000"),
                        "sentCount", 0), null);
            }
        };
        PolicyExecutor ex = new PolicyExecutor(List.of(failingSms), new InMemoryIdempotencyStore());

        PolicyExecutionResult r = ex.execute(
                resultWith(plan("R1", "SEND_SMS", 10, "k1")),
                ctx, FailureStrategy.CONTINUE_ON_ERROR, tenant);

        assertThat(r.actions().get(0).status()).isEqualTo(ActionExecutionStatus.FAILED);
        assertThat(r.actions().get(0).error()).isEqualTo("No real SMS sender available");
        assertThat(r.actions().get(0).resultPayload())
                .containsEntry("channel", "sms")
                .containsEntry("sentCount", 0);
        assertThat((List<String>) r.actions().get(0).resultPayload().get("targetPhones"))
                .containsExactly("+8613800138000");
    }

    @Test
    void idempotency_alreadySucceededIsSkipped() {
        List<String> exec = new ArrayList<>();
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        store.record(tenant, ActionExecutionResult.of("R1", "NOTIFY", "k1", ActionExecutionStatus.SUCCESS));
        PolicyExecutor ex = new PolicyExecutor(List.of(new RecordingHandler("NOTIFY", false, exec)), store);
        PolicyExecutionResult r = ex.execute(resultWith(plan("R1", "NOTIFY", 10, "k1")),
                ctx, FailureStrategy.CONTINUE_ON_ERROR, tenant);
        assertThat(r.actions().get(0).status()).isEqualTo(ActionExecutionStatus.SKIPPED);
        assertThat(exec).isEmpty(); // handler not invoked
    }

    @Test
    void failFast_stopsRemaining() {
        List<String> exec = new ArrayList<>();
        PolicyExecutor ex = new PolicyExecutor(List.of(
                new RecordingHandler("NOTIFY", true, exec), new RecordingHandler("CREATE_TASK", false, exec)),
                new InMemoryIdempotencyStore());
        PolicyExecutionResult r = ex.execute(
                resultWith(plan("R1", "NOTIFY", 10, "k1"), plan("R2", "CREATE_TASK", 20, "k2")),
                ctx, FailureStrategy.FAIL_FAST, tenant);
        assertThat(r.actions().get(0).status()).isEqualTo(ActionExecutionStatus.FAILED);
        assertThat(r.actions().get(1).status()).isEqualTo(ActionExecutionStatus.NOT_EXECUTED);
        assertThat(exec).containsExactly("k1"); // second never ran
    }

    @Test
    void failFast_recordsNotExecutedActionsForTraceEvidence() {
        List<String> exec = new ArrayList<>();
        RecordingStore store = new RecordingStore();
        PolicyExecutor ex = new PolicyExecutor(List.of(
                new RecordingHandler("NOTIFY", true, exec),
                new RecordingHandler("CREATE_TASK", false, exec)),
                store);

        PolicyExecutionResult r = ex.execute(
                resultWith(plan("R1", "NOTIFY", 10, "k1"), plan("R2", "CREATE_TASK", 20, "k2")),
                ctx, FailureStrategy.FAIL_FAST, tenant);

        assertThat(r.actions()).extracting(ActionExecutionResult::status)
                .containsExactly(ActionExecutionStatus.FAILED, ActionExecutionStatus.NOT_EXECUTED);
        assertThat(store.records).extracting(ActionExecutionResult::status)
                .containsExactly(ActionExecutionStatus.FAILED, ActionExecutionStatus.NOT_EXECUTED);
        assertThat(store.records.get(1).idempotencyKey()).isEqualTo("k2");
    }

    @Test
    void continueOnError_partialSuccess() {
        List<String> exec = new ArrayList<>();
        PolicyExecutor ex = new PolicyExecutor(List.of(
                new RecordingHandler("NOTIFY", true, exec), new RecordingHandler("CREATE_TASK", false, exec)),
                new InMemoryIdempotencyStore());
        PolicyExecutionResult r = ex.execute(
                resultWith(plan("R1", "NOTIFY", 10, "k1"), plan("R2", "CREATE_TASK", 20, "k2")),
                ctx, FailureStrategy.CONTINUE_ON_ERROR, tenant);
        assertThat(r.overallStatus()).isEqualTo(PolicyExecutionResult.OverallStatus.PARTIAL_SUCCESS);
        assertThat(exec).containsExactly("k1", "k2");
    }

    @Test
    void noHandler_isNoHandlerStatus() {
        PolicyExecutor ex = new PolicyExecutor(List.of(), new InMemoryIdempotencyStore());
        PolicyExecutionResult r = ex.execute(resultWith(plan("R1", "WEBHOOK", 10, "k1")),
                ctx, FailureStrategy.CONTINUE_ON_ERROR, tenant);
        assertThat(r.actions().get(0).status()).isEqualTo(ActionExecutionStatus.NO_HANDLER);
    }

    @Test
    void allOrNothing_throwsOnFailure() {
        List<String> exec = new ArrayList<>();
        PolicyExecutor ex = new PolicyExecutor(List.of(new RecordingHandler("NOTIFY", true, exec)),
                new InMemoryIdempotencyStore());
        assertThatThrownBy(() -> ex.execute(resultWith(plan("R1", "NOTIFY", 10, "k1")),
                ctx, FailureStrategy.ALL_OR_NOTHING, tenant))
                .isInstanceOf(PolicyExecutor.PolicyExecutionException.class);
    }

    @Test
    void retryAsync_marksRetryPending() {
        List<String> exec = new ArrayList<>();
        PolicyExecutor ex = new PolicyExecutor(List.of(new RecordingHandler("NOTIFY", true, exec)),
                new InMemoryIdempotencyStore());
        PolicyExecutionResult r = ex.execute(resultWith(plan("R1", "NOTIFY", 10, "k1")),
                ctx, FailureStrategy.RETRY_ASYNC, tenant);
        assertThat(r.actions().get(0).status()).isEqualTo(ActionExecutionStatus.RETRY_PENDING);
    }
}
