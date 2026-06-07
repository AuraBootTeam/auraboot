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
        RecordingHandler(String type, boolean fail, List<String> executed) {
            this.type = type; this.fail = fail; this.executed = executed;
        }
        @Override public boolean supports(String t) { return type.equals(t); }
        @Override public void execute(ResolvedActionPlan plan, DecisionContext c) throws Exception {
            executed.add(plan.idempotencyKey());
            if (fail) throw new IllegalStateException("boom:" + type);
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
