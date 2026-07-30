package com.auraboot.framework.event.config;

import com.auraboot.framework.agent.identity.DelegationGrant;
import com.auraboot.framework.agent.identity.ExecutionPrincipal;
import com.auraboot.framework.agent.identity.ExecutionPrincipalContext;
import com.auraboot.framework.agent.identity.Initiator;
import com.auraboot.framework.agent.runtime.context.ContextEnvelope;
import com.auraboot.framework.agent.runtime.context.ContextEnvelopeContext;
import com.auraboot.framework.agent.runtime.context.ContextEnvelopeFactory;
import com.auraboot.framework.application.tenant.MetaContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TenantAwareTaskDecorator} (IMPL-03).
 *
 * <p>The decorator previously copied only tenant/user/userPid/username via the
 * 4-arg {@code setContext}, silently dropping roleIds, memberId, envId and the
 * OTel trace id. IM {@code @AI} / group-chat {@code @Async} workers therefore
 * lost their environment scope and had a broken trace. These tests pin the full
 * snapshot/restore propagation across a real thread boundary, and assert the
 * request-scoped {@code *_BYPASSED} guard flags are NOT propagated.
 */
class TenantAwareTaskDecoratorTest {

    @AfterEach
    void tearDown() {
        ExecutionPrincipalContext.clear();
        ContextEnvelopeContext.clear();
        MetaContext.clear();
    }

    @Test
    void decorate_propagatesFullIdentityAndCorrelationToWorkerThread() throws Exception {
        MetaContext.setContext(7L, 42L, "usr_pid", "alice", Set.of(1L, 2L));
        MetaContext.setMemberId(99L);
        MetaContext.setEnvironmentId(5L);
        MetaContext.setOtelTraceId("trace-abc");

        AtomicReference<Long> tenant = new AtomicReference<>();
        AtomicReference<Long> user = new AtomicReference<>();
        AtomicReference<Long> member = new AtomicReference<>();
        AtomicReference<Long> env = new AtomicReference<>();
        AtomicReference<String> otel = new AtomicReference<>();
        AtomicReference<Set<Long>> roles = new AtomicReference<>();

        Runnable decorated = new TenantAwareTaskDecorator().decorate(() -> {
            tenant.set(MetaContext.getCurrentTenantId());
            user.set(MetaContext.getCurrentUserId());
            member.set(MetaContext.getCurrentMemberId());
            env.set(MetaContext.getCurrentEnvironmentId());
            otel.set(MetaContext.getOtelTraceId());
            roles.set(MetaContext.getCurrentRoleIds());
        });

        Thread worker = new Thread(decorated);
        worker.start();
        worker.join();

        assertThat(tenant.get()).isEqualTo(7L);
        assertThat(user.get()).isEqualTo(42L);
        assertThat(roles.get()).containsExactlyInAnyOrder(1L, 2L);
        // The three regression fields the old 4-field decorator dropped:
        assertThat(member.get()).as("memberId must propagate").isEqualTo(99L);
        assertThat(env.get()).as("envId must propagate").isEqualTo(5L);
        assertThat(otel.get()).as("otel trace id must propagate").isEqualTo("trace-abc");
    }

    @Test
    void decorate_clearsWorkerThreadContextAfterRun() throws Exception {
        MetaContext.setContext(7L, 42L, "p", "u");
        Runnable decorated = new TenantAwareTaskDecorator().decorate(() -> { /* no-op */ });

        AtomicBoolean clearedAfter = new AtomicBoolean(false);
        Thread worker = new Thread(() -> {
            decorated.run();
            clearedAfter.set(!MetaContext.exists());
        });
        worker.start();
        worker.join();

        assertThat(clearedAfter.get()).as("worker MetaContext must be cleared after run").isTrue();
    }

    @Test
    void decorate_doesNotPropagateCommandPermitPlan() throws Exception {
        MetaContext.setContext(7L, 42L, "p", "u");
        AtomicBoolean workerSawPermit = new AtomicBoolean(true);

        // Snapshot is taken inside a command permit. Authority must NOT leak into the worker.
        Runnable decorated = MetaContext.runWithCommandPermitScope("ALL", () ->
                new TenantAwareTaskDecorator().decorate(() ->
                        workerSawPermit.set(MetaContext.hasCommandPermitScope())));

        Thread worker = new Thread(decorated);
        worker.start();
        worker.join();

        assertThat(workerSawPermit.get())
                .as("command permit must not leak into async worker")
                .isFalse();
    }

    @Test
    void decorate_returnsRunnableUnchangedWhenNoContext() {
        MetaContext.clear();
        Runnable original = () -> { };
        assertThat(new TenantAwareTaskDecorator().decorate(original)).isSameAs(original);
    }

    @Test
    void decorate_propagatesExecutionPrincipalAndClearsItAfterRun() throws Exception {
        MetaContext.setContext(7L, 301L, "USR_AGENT", "agent", Set.of(11L));
        MetaContext.setMemberId(401L);
        ExecutionPrincipal principal = new ExecutionPrincipal(
                7L,
                301L,
                401L,
                "USR_AGENT",
                "agent",
                501L,
                "EMP_AGENT",
                Initiator.human(101L, 201L, "im_group"),
                DelegationGrant.employeeAutonomous(),
                "sales_colleague",
                "AGENT_RELEASE_1",
                "DEPLOYMENT_1",
                "release-hash-1",
                "im_group",
                ExecutionPrincipal.Type.DIGITAL_EMPLOYEE,
                Set.of(11L));
        ExecutionPrincipalContext.restore(principal);
        ContextEnvelope envelope = new ContextEnvelopeFactory().compile(
                new ContextEnvelopeFactory.CompileRequest(
                        "TURN_ASYNC",
                        principal,
                        "im_group",
                        null,
                        null,
                        91L,
                        "ACP_RUN",
                        Set.of(),
                        List.of("KB_A"),
                        Map.of(),
                        "zh-CN",
                        "Asia/Shanghai",
                        Instant.parse("2026-07-29T10:00:00Z")));
        ContextEnvelopeContext.restore(envelope);

        AtomicReference<ExecutionPrincipal> workerPrincipal = new AtomicReference<>();
        AtomicReference<ContextEnvelope> workerEnvelope = new AtomicReference<>();
        AtomicBoolean clearedAfter = new AtomicBoolean(false);
        Runnable decorated = new TenantAwareTaskDecorator().decorate(() -> {
            workerPrincipal.set(ExecutionPrincipalContext.requireCurrent());
            workerEnvelope.set(ContextEnvelopeContext.current().orElseThrow());
        });

        Thread worker = new Thread(() -> {
            decorated.run();
            clearedAfter.set(
                    ExecutionPrincipalContext.current().isEmpty()
                            && ContextEnvelopeContext.current().isEmpty());
        });
        worker.start();
        worker.join();

        assertThat(workerPrincipal.get()).isEqualTo(principal);
        assertThat(workerEnvelope.get().envelopeHash()).isEqualTo(envelope.envelopeHash());
        assertThat(clearedAfter.get()).isTrue();
    }
}
