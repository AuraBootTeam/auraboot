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
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

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
            @Override public Map<String, Object> executeWithResult(ResolvedActionPlan p, DecisionContext c) {
                execute(p, c);
                return Map.of("dispatchId", "notify-" + p.idempotencyKey(), "sentCount", 1);
            }
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

        Map<String, Object> logPayload = jdbcTemplate.queryForMap(
                "select result_payload from ab_drt_policy_exec_log where tenant_id = ? and idempotency_key = ?",
                tenant, key);
        assertThat(String.valueOf(logPayload.get("result_payload")))
                .contains("\"dispatchId\"")
                .contains("\"sentCount\"");

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
    private org.springframework.transaction.support.TransactionTemplate actionTransaction() {
        var template = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        template.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    @Test void concurrentDeliveriesExecuteOnlyOneCommittedSideEffect() throws Exception {
        Long tenant = getTestTenant().getId();
        String key = "parallel-" + System.nanoTime();
        AtomicInteger invoked = new AtomicInteger();
        var start = new java.util.concurrent.CountDownLatch(1);
        var identity = com.auraboot.framework.application.tenant.MetaContext.snapshot();
        ActionHandler handler = new ActionHandler() {
            @Override public boolean supports(String type) { return "NOTIFY".equals(type); }
            @Override public void execute(ResolvedActionPlan plan, DecisionContext context) { invoked.incrementAndGet(); }
        };
        var executor = new PolicyExecutor(() -> List.of(handler), idempotencyStore, actionTransaction());
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var calls = new java.util.ArrayList<java.util.concurrent.Future<PolicyExecutionResult>>();
            for (int i = 0; i < 2; i++) calls.add(workers.submit(() -> {
                start.await(10, java.util.concurrent.TimeUnit.SECONDS);
                try {
                    com.auraboot.framework.application.tenant.MetaContext.restore(identity);
                    return executor.execute(oneNotify(key), ctx, FailureStrategy.FAIL_FAST, tenant);
                } finally { com.auraboot.framework.application.tenant.MetaContext.clear(); }
            }));
            start.countDown();
            var statuses = new java.util.ArrayList<ActionExecutionStatus>();
            for (var call : calls) statuses.add(call.get(20, java.util.concurrent.TimeUnit.SECONDS).actions().getFirst().status());
            assertThat(statuses).containsExactlyInAnyOrder(ActionExecutionStatus.SUCCESS, ActionExecutionStatus.SKIPPED);
            assertThat(invoked.get()).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("select count(*) from ab_drt_policy_exec_log where tenant_id=? and idempotency_key=? and status='SUCCESS'", Integer.class, tenant, key)).isEqualTo(1);
        } finally {
            jdbcTemplate.update("delete from ab_drt_policy_exec_log where tenant_id=? and idempotency_key=?", tenant, key);
        }
    }

    @Test void failingActionRollsBackItsDatabaseWritesButKeepsFailureOutcome() {
        Long tenant = getTestTenant().getId();
        String key = "rollback-" + System.nanoTime(), marker = key + "-side-effect";
        ActionHandler handler = new ActionHandler() {
            @Override public boolean supports(String type) { return "NOTIFY".equals(type); }
            @Override public void execute(ResolvedActionPlan plan, DecisionContext context) {
                idempotencyStore.record(tenant, "test", new ActionExecutionResult("R1", "NOTIFY", marker, ActionExecutionStatus.SUCCESS, null));
                throw new IllegalStateException("Business mutation must roll back");
            }
        };
        var executor = new PolicyExecutor(() -> List.of(handler), idempotencyStore, actionTransaction());
        try {
            var result = executor.execute(oneNotify(key), ctx, FailureStrategy.FAIL_FAST, tenant);
            assertThat(result.overallStatus()).isEqualTo(PolicyExecutionResult.OverallStatus.FAILED);
            assertThat(jdbcTemplate.queryForObject("select count(*) from ab_drt_policy_exec_log where tenant_id=? and idempotency_key=?", Integer.class, tenant, marker)).isZero();
            assertThat(jdbcTemplate.queryForObject("select status from ab_drt_policy_exec_log where tenant_id=? and idempotency_key=?", String.class, tenant, key)).isEqualTo("FAILED");
        } finally { jdbcTemplate.update("delete from ab_drt_policy_exec_log where tenant_id=? and idempotency_key in (?,?)", tenant, key, marker); }
    }

}
