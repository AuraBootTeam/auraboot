package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.agent.port.ToolDiscoveryPort;
import com.auraboot.framework.application.tenant.MetaContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ACP P0-5: ChatToolExecutor stateless invariant.
 *
 * <p>StepLoopService parallel-tool dispatch shares a single ChatToolExecutor
 * instance across worker threads. This test asserts the executor has no
 * mutable per-call state and can be invoked concurrently without leaking
 * tenant context or producing wrong tool routings.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatToolExecutor concurrency invariants (P0-5)")
class ChatToolExecutorConcurrencyTest {

    @AfterEach
    void teardown() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("execute_invokedFrom10Threads_noStateLeak")
    void execute_invokedFrom10Threads_noStateLeak() throws Exception {
        // Stub ToolDiscoveryPort echoes the resolved tool code + tenant id back
        // so we can detect any cross-thread cross-talk.
        ConcurrentHashMap<String, Long> seenTenants = new ConcurrentHashMap<>();
        Set<String> calls = ConcurrentHashMap.newKeySet();
        ToolDiscoveryPort port = new ToolDiscoveryPort() {
            @Override
            public java.util.List<ToolDef> discoverTools(Long tenantId, java.util.List<String> candidateSkills,
                                                          String modelHint, String intentHint, int maxTools) {
                return java.util.List.of();
            }
            @Override
            public Map<String, Object> executeTool(Long tenantId, String toolCode, Map<String, Object> input) {
                seenTenants.put(toolCode, tenantId);
                calls.add(toolCode + "::" + tenantId);
                Map<String, Object> r = new java.util.LinkedHashMap<>();
                r.put("success", true);
                r.put("tool", toolCode);
                r.put("tenant", tenantId);
                return r;
            }
        };

        ChatToolExecutor executor = new ChatToolExecutor(port, null, null,
                new com.fasterxml.jackson.databind.ObjectMapper());

        int N = 10;
        ExecutorService pool = Executors.newFixedThreadPool(N);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(N);
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < N; i++) {
            final long tenantId = 100L + i;
            final String toolName = "nq_tool_" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    MetaContext.setContext(tenantId, 1L, "u" + tenantId, "user" + tenantId);
                    Map<String, Object> result = executor.execute(toolName, Map.of("k", "v"), null);
                    assertThat(result.get("success")).isEqualTo(true);
                    assertThat(result.get("tenant")).isEqualTo(tenantId);
                } catch (Throwable t) {
                    errors.incrementAndGet();
                    t.printStackTrace();
                } finally {
                    MetaContext.clear();
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(errors.get()).isZero();
        assertThat(calls).hasSize(N);
        // Each tool routed against its own tenant — no leakage
        for (int i = 0; i < N; i++) {
            assertThat(seenTenants.get("nq:tool_" + i)).isEqualTo(100L + i);
        }
    }
}
