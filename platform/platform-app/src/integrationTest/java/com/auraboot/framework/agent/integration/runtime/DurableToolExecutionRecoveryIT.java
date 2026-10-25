package com.auraboot.framework.agent.integration.runtime;

import com.auraboot.framework.agent.runtime.*;

import com.auraboot.framework.agent.authorization.EffectClass;
import com.auraboot.framework.agent.identity.*;
import com.auraboot.framework.agent.provider.ProviderExecutionResult;
import com.auraboot.framework.agent.provider.ToolProviderRegistry;
import com.auraboot.framework.agent.runtime.context.*;
import com.auraboot.framework.agent.service.ActionRecorder;
import com.auraboot.framework.agent.service.ToolAclChecker;
import com.auraboot.framework.application.tenant.MetaContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Production ledger and recovery against an owned, migrated PostgreSQL database. */
class DurableToolExecutionRecoveryIT {
    private JdbcTemplate jdbc;
    private JdbcDurableToolExecutionLedger ledger;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ContextEnvelopeFactory factory = new ContextEnvelopeFactory();
    private final long tenant = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    private final String run = UUID.randomUUID().toString().replace("-", "").substring(0, 26);
    private final String task = UUID.randomUUID().toString().replace("-", "").substring(0, 26);
    private ToolProviderRegistry provider;
    private ActionRecorder recorder;
    private DurableToolExecutionRecoveryService recovery;

    @BeforeEach
    void setup() throws Exception {
        var ds = new PGSimpleDataSource();
        ds.setUrl(Objects.requireNonNull(System.getenv("TEST_DATABASE_URL")));
        ds.setUser(Objects.requireNonNull(System.getenv("TEST_DATABASE_USERNAME")));
        ds.setPassword(System.getenv("TEST_DATABASE_PASSWORD"));
        jdbc = new JdbcTemplate(ds);
        ledger = new JdbcDurableToolExecutionLedger(jdbc, mapper);
        provider = mock(ToolProviderRegistry.class);
        recorder = mock(ActionRecorder.class);
        recovery = new DurableToolExecutionRecoveryService(ledger, provider, mapper, recorder,
                jdbc, factory, new ToolAclChecker(jdbc));
        var principal = new ExecutionPrincipal(tenant, 91L, 92L, "actor", "agent", 93L, "employee",
                Initiator.human(11L, 12L, "web"), DelegationGrant.employeeAutonomous(),
                "recovery-fixture", "release", "deployment", "release-hash", "web",
                ExecutionPrincipal.Type.DIGITAL_EMPLOYEE, Set.of(7L));
        var envelope = factory.compile(new ContextEnvelopeFactory.CompileRequest("turn-" + run,
                principal, "web", "profile", "session", 9L, "ACP_RUN", Set.of(), List.of(), Map.of(),
                "en", "UTC", Instant.now()));
        jdbc.update("INSERT INTO ab_agent_task (pid, tenant_id, title, task_status) VALUES (?, ?, ?, 'blocked')",
                task, tenant, "Owned S5 recovery fixture");
        jdbc.update("""
                INSERT INTO ab_agent_run (pid, tenant_id, task_id, agent_id, run_status,
                    actor_user_id, actor_member_id, context_envelope, context_envelope_hash)
                VALUES (?, ?, ?, 'recovery-fixture', 'failed', 91, 92, ?::jsonb, ?)
                """, run, tenant, task, mapper.writeValueAsString(envelope), envelope.envelopeHash());
    }

    private DurableToolExecutionRequest request(long scope) {
        return new DurableToolExecutionRequest(scope, run, task, "recovery-fixture", "custom:close_ticket",
                "custom:close_ticket", "args-hash", "custom", Set.of(EffectClass.EXTERNAL_NETWORK),
                Map.of("idempotencyKey", "external-" + run));
    }

    private DurableToolExecutionRecord dueFailure() {
        var request = request(tenant);
        assertThat(ledger.claim(request).acquired()).isTrue();
        ledger.fail(request, request.executionKey(), "{\"success\":false}", "provider timeout");
        // The fixture advances only its own retry clock, without sleeping or changing production backoff.
        jdbc.update("UPDATE ab_idempotency_record SET outcome=jsonb_set(outcome, '{nextRetryAt}', '0') WHERE tenant_id=? AND client_request_id=?",
                tenant, request.executionKey());
        return ledger.findRecoverable(500).stream().filter(r -> r.request().tenantId() == tenant)
                .findFirst().orElseThrow();
    }

    private DurableToolExecutionRecord current() {
        return ledger.claim(request(tenant)).record();
    }

    @Test
    void successfulSideEffectIsStableDespiteLateFailureAndCompensationSignals() {
        var request = request(tenant);
        assertThat(ledger.claim(request).acquired()).isTrue();
        ledger.complete(request, request.executionKey(), "{\"externalId\":\"owned-result\"}");
        var success = current();
        ledger.fail(request, request.executionKey(), "{}", "late audit exception");
        ledger.markCompensationRequired(success, "late worker");
        assertThat(current().status()).isEqualTo(DurableToolExecutionStatus.SUCCEEDED);
        assertThat(current().result()).containsEntry("externalId", "owned-result");
    }

    @Test
    void stalePollCannotBypassNewBackoffOrAttemptBudget() {
        var stale = dueFailure();
        assertThat(ledger.claimRetry(stale)).isTrue();
        ledger.fail(stale.request(), stale.executionKey(), "{}", "second timeout");
        assertThat(ledger.claimRetry(stale)).isFalse();
        assertThat(ledger.claimRetry(current())).isFalse();
        jdbc.update("UPDATE ab_idempotency_record SET outcome=jsonb_set(outcome, '{nextRetryAt}', '0') WHERE tenant_id=? AND client_request_id=?",
                tenant, stale.executionKey());
        var dueAgain = current();
        assertThat(ledger.claimRetry(stale)).as("even a due row rejects an earlier attempt snapshot").isFalse();
        assertThat(ledger.claimRetry(dueAgain)).isTrue();
        ledger.fail(stale.request(), stale.executionKey(), "{}", "final provider timeout");
        jdbc.update("UPDATE ab_idempotency_record SET status='FAILED', outcome=jsonb_set(jsonb_set(jsonb_set(outcome, '{status}', '\"FAILED\"'), '{attemptCount}', '3'), '{nextRetryAt}', '0') WHERE tenant_id=? AND client_request_id=?",
                tenant, stale.executionKey());
        assertThat(ledger.claimRetry(current())).isFalse();
    }

    @Test
    void twoWorkersCanClaimOneRetryOnly() throws Exception {
        var record = dueFailure();
        var start = new CyclicBarrier(3);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> { start.await(); return ledger.claimRetry(record); });
            var second = executor.submit(() -> { start.await(); return ledger.claimRetry(record); });
            start.await(5, TimeUnit.SECONDS);
            assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(current().attemptCount()).isEqualTo(2);
    }

    @Test
    void sameExecutionKeyIsIndependentAcrossTenants() {
        var own = request(tenant);
        var other = request(tenant + 1);
        assertThat(own.executionKey()).isEqualTo(other.executionKey());
        assertThat(ledger.claim(own).acquired()).isTrue();
        assertThat(ledger.claim(other).acquired()).isTrue();
        ledger.complete(own, own.executionKey(), "{\"owner\":true}");
        assertThat(ledger.claim(other).record().status()).isEqualTo(DurableToolExecutionStatus.RUNNING);
    }

    @Test
    void realRetryRestoresActorAndCannotReexecuteAfterAuditFailure() {
        dueFailure();
        when(provider.execute(eq(tenant), eq("custom:close_ticket"), anyMap())).thenAnswer(call -> {
            assertThat(MetaContext.getCurrentTenantId()).isEqualTo(tenant);
            assertThat(ExecutionPrincipalContext.requireCurrent().actorUserId()).isEqualTo(91L);
            assertThat(ContextEnvelopeContext.current().orElseThrow().agentCode()).isEqualTo("recovery-fixture");
            return ProviderExecutionResult.builder().success(true).data(Map.of("externalId", run)).build();
        });
        doThrow(new IllegalStateException("injected audit storage failure")).when(recorder)
                .recordProviderAction(any(), any(), any(), any(), any(), any(), any(), any());
        recovery.processDue();
        assertThat(current().status()).isEqualTo(DurableToolExecutionStatus.SUCCEEDED);
        assertThat(current().result()).containsEntry("externalId", run);
        recovery.processDue();
        verify(provider, times(1)).execute(eq(tenant), anyString(), anyMap());
        assertThat(ExecutionPrincipalContext.current()).isEmpty();
        assertThat(ContextEnvelopeContext.current()).isEmpty();
    }

    @Test
    void cancelledRunCannotDispatchRetry() {
        dueFailure();
        jdbc.update("UPDATE ab_agent_run SET run_status='cancelled' WHERE tenant_id=? AND pid=?", tenant, run);
        assertRejectedWithoutDispatch();
    }

    @Test
    void expiredRunCannotDispatchRetry() {
        dueFailure();
        jdbc.update("UPDATE ab_agent_run SET timeout_at=NOW()-INTERVAL '1 minute' WHERE tenant_id=? AND pid=?", tenant, run);
        assertRejectedWithoutDispatch();
    }

    @Test
    void tamperedEnvelopeCannotBorrowAnotherActor() {
        dueFailure();
        jdbc.update("UPDATE ab_agent_run SET actor_user_id=999 WHERE tenant_id=? AND pid=?", tenant, run);
        assertRejectedWithoutDispatch();
    }

    @Test
    void currentAclDenialPreventsHistoricalRetry() {
        dueFailure();
        jdbc.update("INSERT INTO ab_agent_tool_acl (pid, tenant_id, tool_ref_pattern, effect, reason) VALUES (?, ?, 'custom:*', 'deny', 'revoked')",
                UUID.randomUUID().toString().replace("-", "").substring(0, 26), tenant);
        assertRejectedWithoutDispatch();
    }

    @Test
    void crashedWorkerIsFencedIntoReconciliationWithoutRepeatingItsExternalEffect() {
        var request = request(tenant);
        assertThat(ledger.claim(request).acquired()).isTrue();
        ledger.markAbandonedExecutions(System.currentTimeMillis() - 900000);
        assertThat(current().status()).isEqualTo(DurableToolExecutionStatus.RUNNING);
        jdbc.update("UPDATE ab_idempotency_record SET outcome=jsonb_set(outcome, '{updatedAt}', '1') WHERE tenant_id=? AND client_request_id=?",
                tenant, request.executionKey());
        recovery.processDue();
        assertThat(current().status()).isEqualTo(DurableToolExecutionStatus.COMPENSATION_REQUIRED);
        assertThat(current().compensationReason()).contains("lease expired");
        verifyNoInteractions(provider);
        assertThatThrownBy(() -> ledger.complete(request, request.executionKey(), "{}"))
                .isInstanceOf(IllegalStateException.class);
        long fencedAt = current().updatedAt();
        ledger.markAbandonedExecutions(System.currentTimeMillis() - 900000);
        assertThat(current().updatedAt()).isEqualTo(fencedAt);
        assertThat(current().request().input()).containsEntry("idempotencyKey", "external-" + run);
    }

    private void assertRejectedWithoutDispatch() {
        recovery.processDue();
        verifyNoInteractions(provider);
        assertThat(current().status()).isEqualTo(DurableToolExecutionStatus.COMPENSATION_REQUIRED);
        assertThat(current().compensationReason()).contains("Recovery context rejected");
        assertThat(ExecutionPrincipalContext.current()).isEmpty();
        assertThat(ContextEnvelopeContext.current()).isEmpty();
    }
}
