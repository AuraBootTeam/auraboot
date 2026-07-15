package com.auraboot.framework.event.config;

import com.auraboot.framework.application.tenant.MetaContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
    void decorate_doesNotPropagateRequestScopedBypassFlag() throws Exception {
        MetaContext.setContext(7L, 42L, "p", "u");
        AtomicBoolean workerSawBypass = new AtomicBoolean(true);

        // Snapshot is taken inside a data-permission-bypass block. The bypass is a
        // request-scoped relaxation and must NOT leak into the async worker.
        Runnable decorated = MetaContext.runWithoutDataPermission(() ->
                new TenantAwareTaskDecorator().decorate(() ->
                        workerSawBypass.set(MetaContext.isDataPermissionBypassed())));

        Thread worker = new Thread(decorated);
        worker.start();
        worker.join();

        assertThat(workerSawBypass.get())
                .as("data-permission bypass must not leak into async worker")
                .isFalse();
    }

    @Test
    void decorate_returnsRunnableUnchangedWhenNoContext() {
        MetaContext.clear();
        Runnable original = () -> { };
        assertThat(new TenantAwareTaskDecorator().decorate(original)).isSameAs(original);
    }
}
