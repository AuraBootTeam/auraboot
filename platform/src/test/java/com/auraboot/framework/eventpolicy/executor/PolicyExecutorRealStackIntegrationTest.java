package com.auraboot.framework.eventpolicy.executor;

import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.eventpolicy.model.EventPolicyResult;
import com.auraboot.framework.eventpolicy.model.FailureStrategy;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-stack idempotency for the PolicyExecutor: the {@link DbIdempotencyStore} persists executions
 * to {@code ab_drt_policy_exec_log} (real Postgres), so replaying the same action plan SKIPS it and
 * the handler is not invoked twice (docs/2.md §8.2).
 */
class PolicyExecutorRealStackIntegrationTest extends BaseIntegrationTest {

    @Autowired private DbIdempotencyStore idempotencyStore;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final DecisionContext ctx = DecisionContext.of(Map.of());

    private EventPolicyResult oneNotify(String key) {
        ResolvedActionPlan plan = new ResolvedActionPlan("R1", "NOTIFY", "ROLE:m", 10, Map.of(), key);
        return new EventPolicyResult("p", EventPolicyResult.Status.MATCHED,
                List.of("R1"), List.of(), List.of(plan), List.of());
    }

    @Test
    void replayedActionPlanIsSkipped_overRealPostgres() {
        Long tenant = getTestTenant().getId();
        String key = "exec-it-" + System.nanoTime();
        AtomicInteger invocations = new AtomicInteger();

        ActionHandler notifyHandler = new ActionHandler() {
            @Override public boolean supports(String t) { return "NOTIFY".equals(t); }
            @Override public void execute(ResolvedActionPlan p, DecisionContext c) { invocations.incrementAndGet(); }
        };
        PolicyExecutor executor = new PolicyExecutor(List.of(notifyHandler), idempotencyStore);

        // first run: executes + records SUCCESS in ab_drt_policy_exec_log
        PolicyExecutionResult first = executor.execute(oneNotify(key), ctx, FailureStrategy.RETRY_ASYNC, tenant);
        assertThat(first.actions().get(0).status()).isEqualTo(ActionExecutionStatus.SUCCESS);
        assertThat(invocations.get()).isEqualTo(1);

        Integer rows = jdbcTemplate.queryForObject(
                "select count(*) from ab_drt_policy_exec_log where tenant_id = ? and idempotency_key = ? and status = 'SUCCESS'",
                Integer.class, tenant, key);
        assertThat(rows).isEqualTo(1);

        // replay (same key): SKIPPED, handler NOT invoked again, still one row
        PolicyExecutionResult second = executor.execute(oneNotify(key), ctx, FailureStrategy.RETRY_ASYNC, tenant);
        assertThat(second.actions().get(0).status()).isEqualTo(ActionExecutionStatus.SKIPPED);
        assertThat(invocations.get()).isEqualTo(1); // not re-run

        Integer rowsAfter = jdbcTemplate.queryForObject(
                "select count(*) from ab_drt_policy_exec_log where tenant_id = ? and idempotency_key = ?",
                Integer.class, tenant, key);
        assertThat(rowsAfter).isEqualTo(1);
    }

    @Test
    void failedThenRetrySuccess_updatesSameRow() {
        Long tenant = getTestTenant().getId();
        String key = "exec-retry-" + System.nanoTime();

        // record a prior FAILED outcome
        idempotencyStore.record(tenant, new ActionExecutionResult("R1", "NOTIFY", key,
                ActionExecutionStatus.FAILED, "boom"));
        assertThat(idempotencyStore.alreadySucceeded(tenant, key)).isFalse(); // failed != succeeded

        // retry succeeds → same row updated to SUCCESS (no duplicate-key violation)
        ActionHandler ok = new ActionHandler() {
            @Override public boolean supports(String t) { return "NOTIFY".equals(t); }
            @Override public void execute(ResolvedActionPlan p, DecisionContext c) { }
        };
        PolicyExecutor executor = new PolicyExecutor(List.of(ok), idempotencyStore);
        executor.execute(oneNotify(key), ctx, FailureStrategy.RETRY_ASYNC, tenant);

        assertThat(idempotencyStore.alreadySucceeded(tenant, key)).isTrue();
        Integer rows = jdbcTemplate.queryForObject(
                "select count(*) from ab_drt_policy_exec_log where tenant_id = ? and idempotency_key = ?",
                Integer.class, tenant, key);
        assertThat(rows).isEqualTo(1); // updated, not duplicated
    }
}
