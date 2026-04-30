package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.config.AgentProperties;
import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.trace.AiTraceService;
import com.auraboot.framework.agent.trace.TraceContext;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.event.config.TenantAwareTaskDecorator;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ACP P0-5 Parallel Tool Calls — StepLoopService.processToolUseBlocksParallel.
 *
 * <p>These exercise the orchestration logic (fanout, approval-serial-first, ordering,
 * MetaContext propagation, kill-switch) with a real ThreadPoolTaskExecutor wired
 * with TenantAwareTaskDecorator. ToolLoopService.executeToolCall is replaced by a
 * lightweight stub that records its execution thread + timing so we can assert
 * actual concurrency without standing up the full Spring context.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StepLoopService parallel tool dispatch (P0-5)")
class StepLoopParallelToolTest {

    @Mock private DynamicDataMapper dynamicDataMapper;
    @Mock private LlmProviderFactory providerFactory;
    @Mock private AiTraceService aiTraceService;
    @Mock private AgentApprovalGateService approvalGate;

    private RecordingToolLoopService toolLoopService;
    private StepLoopService stepLoopService;
    private AgentProperties agentProperties;
    private ThreadPoolTaskExecutor executor;

    @BeforeEach
    void setup() {
        toolLoopService = new RecordingToolLoopService();
        agentProperties = new AgentProperties();
        agentProperties.setParallel(new AgentProperties.Parallel());
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("test-async-");
        executor.setTaskDecorator(new TenantAwareTaskDecorator());
        executor.initialize();
        stepLoopService = new StepLoopService(
                toolLoopService,
                dynamicDataMapper,
                new ObjectMapper(),
                providerFactory,
                aiTraceService,
                approvalGate,
                agentProperties,
                executor);
    }

    @AfterEach
    void teardown() {
        executor.shutdown();
        MetaContext.clear();
        StepContext.clear();
    }

    // -------------------------------------------------------------------
    // 1) 3 independent read tools execute concurrently — total ≈ max, not sum
    // -------------------------------------------------------------------
    @Test
    @DisplayName("parallelToolCalls_threeIndependentReadTools_executeConcurrently")
    void parallelToolCalls_threeIndependentReadTools_executeConcurrently() throws Exception {
        MetaContext.setContext(101L, 9L, "user-pid", "tester");
        toolLoopService.sleepMs = 200; // each tool 200ms

        List<AgentToolDefinition> tools = Arrays.asList(
                readTool("nq:list_users"),
                readTool("nq:list_orders"),
                readTool("nq:list_products"));

        List<LlmChatResponse.ContentBlock> blocks = Arrays.asList(
                toolBlock("call_1", "nq:list_users"),
                toolBlock("call_2", "nq:list_orders"),
                toolBlock("call_3", "nq:list_products"));

        long start = System.currentTimeMillis();
        List<StepLoopService.ToolResult> results = stepLoopService.processToolUseBlocksParallel(
                blocks, tools, 101L, "run_001", "task_001", "agent_a", TraceContext.builder().traceId("t").tenantId(101L).build());
        long elapsed = System.currentTimeMillis() - start;

        assertThat(results).hasSize(3);
        assertThat(results.get(0).toolUseId()).isEqualTo("call_1");
        assertThat(results.get(1).toolUseId()).isEqualTo("call_2");
        assertThat(results.get(2).toolUseId()).isEqualTo("call_3");
        // 3 × 200ms serial = 600ms; parallel must come in well below 600ms.
        assertThat(elapsed).as("3-tool parallel batch should finish < 500ms (≈ max), not 600ms (sum)")
                .isLessThan(500);
        // Distinct worker threads used
        assertThat(toolLoopService.threadsUsed).hasSizeGreaterThanOrEqualTo(2);
    }

    // -------------------------------------------------------------------
    // 2) one fails, others complete — failure isolated, not propagated
    // -------------------------------------------------------------------
    @Test
    @DisplayName("parallelToolCalls_oneFails_othersComplete")
    void parallelToolCalls_oneFails_othersComplete() {
        MetaContext.setContext(101L, 9L, "u", "t");
        toolLoopService.failOn = "nq:list_orders";

        List<AgentToolDefinition> tools = Arrays.asList(
                readTool("nq:list_users"),
                readTool("nq:list_orders"),
                readTool("nq:list_products"));
        List<LlmChatResponse.ContentBlock> blocks = Arrays.asList(
                toolBlock("c1", "nq:list_users"),
                toolBlock("c2", "nq:list_orders"),
                toolBlock("c3", "nq:list_products"));

        List<StepLoopService.ToolResult> results = stepLoopService.processToolUseBlocksParallel(
                blocks, tools, 101L, "run_002", null, "agent_a", TraceContext.builder().traceId("t").tenantId(101L).build());

        assertThat(results).hasSize(3);
        assertThat(results.get(0).result()).contains("ok:nq:list_users");
        assertThat(results.get(1).result()).startsWith("Error:");
        assertThat(results.get(2).result()).contains("ok:nq:list_products");
    }

    // -------------------------------------------------------------------
    // 3) approval-required tool runs serial, before parallel batch
    // -------------------------------------------------------------------
    @Test
    @DisplayName("parallelToolCalls_approvalRequiredTool_runsSerial")
    void parallelToolCalls_approvalRequiredTool_runsSerial() {
        MetaContext.setContext(101L, 9L, "u", "t");

        AgentToolDefinition cmdApproval = AgentToolDefinition.builder()
                .name("cmd:delete_account").toolType("dsl_command")
                .sourceCode("cmd:delete_account").requiresApproval(true).riskLevel("L3").build();
        List<AgentToolDefinition> tools = Arrays.asList(
                cmdApproval,
                readTool("nq:list_users"),
                readTool("nq:list_orders"));
        List<LlmChatResponse.ContentBlock> blocks = Arrays.asList(
                toolBlock("c1", "cmd:delete_account"),
                toolBlock("c2", "nq:list_users"),
                toolBlock("c3", "nq:list_orders"));

        List<StepLoopService.ToolResult> results = stepLoopService.processToolUseBlocksParallel(
                blocks, tools, 101L, "run_003", null, "agent_a", TraceContext.builder().traceId("t").tenantId(101L).build());

        assertThat(results).hasSize(3);
        // approval tool ran on the calling thread (serial-first), reads ran on async pool
        String approvalThread = toolLoopService.threadByCall.get("cmd:delete_account");
        String readThread = toolLoopService.threadByCall.get("nq:list_users");
        assertThat(approvalThread).doesNotStartWith("test-async-");
        assertThat(readThread).startsWith("test-async-");
    }

    // -------------------------------------------------------------------
    // 4) fanout exceeded → reject + LLM-readable error, no execution
    // -------------------------------------------------------------------
    @Test
    @DisplayName("parallelToolCalls_fanoutExceedsLimit_rejectsAndReportsToLLM")
    void parallelToolCalls_fanoutExceedsLimit_rejectsAndReportsToLLM() {
        MetaContext.setContext(101L, 9L, "u", "t");
        agentProperties.getParallel().setMaxFanout(5);

        List<AgentToolDefinition> tools = new ArrayList<>();
        List<LlmChatResponse.ContentBlock> blocks = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            tools.add(readTool("nq:tool_" + i));
            blocks.add(toolBlock("c" + i, "nq:tool_" + i));
        }

        List<StepLoopService.ToolResult> results = stepLoopService.processToolUseBlocksParallel(
                blocks, tools, 101L, "run_fanout", null, "agent_a", TraceContext.builder().traceId("t").tenantId(101L).build());

        assertThat(results).hasSize(7);
        // M3: structured JSON envelope so LLM (and any UI surface) recognises
        // a single batch-level error rather than 7 independent tool failures.
        for (StepLoopService.ToolResult r : results) {
            assertThat(r.result())
                    .contains("\"error\":\"batch_fanout_exceeded\"")
                    .contains("\"fanout\":7")
                    .contains("\"max\":5")
                    .contains("\"action\":\"retry_with_fewer_tools\"");
        }
        // No actual tool was executed
        assertThat(toolLoopService.callCount.get()).isEqualTo(0);
    }

    // -------------------------------------------------------------------
    // 5) MetaContext propagated into worker threads via TaskDecorator
    // -------------------------------------------------------------------
    @Test
    @DisplayName("parallelToolCalls_metaContextPropagated")
    void parallelToolCalls_metaContextPropagated() throws Exception {
        MetaContext.setContext(424242L, 7L, "pid-x", "carl");

        List<AgentToolDefinition> tools = Arrays.asList(
                readTool("nq:a"), readTool("nq:b"), readTool("nq:c"));
        List<LlmChatResponse.ContentBlock> blocks = Arrays.asList(
                toolBlock("c1", "nq:a"),
                toolBlock("c2", "nq:b"),
                toolBlock("c3", "nq:c"));

        toolLoopService.captureMetaContext = true;

        stepLoopService.processToolUseBlocksParallel(
                blocks, tools, 424242L, "run_meta", null, "agent_a", TraceContext.builder().traceId("t").tenantId(101L).build());

        assertThat(toolLoopService.tenantIdsObserved).containsOnly(424242L);
        assertThat(toolLoopService.parallelGroupIds).hasSize(3); // each call sees the same group id
        assertThat(toolLoopService.parallelGroupIds.stream().distinct().count())
                .as("all parallel calls should share one group id")
                .isEqualTo(1);
        assertThat(toolLoopService.parallelIndices).containsExactlyInAnyOrder(0, 1, 2);
    }

    // -------------------------------------------------------------------
    // 6) kill switch off → falls back to serial path; no group id stamped
    // -------------------------------------------------------------------
    @Test
    @DisplayName("parallelToolCalls_disabled_fallsBackToSerial")
    void parallelToolCalls_disabled_fallsBackToSerial() {
        agentProperties.getParallel().setEnabled(false);
        MetaContext.setContext(101L, 9L, "u", "t");
        toolLoopService.captureMetaContext = true;
        toolLoopService.sleepMs = 50;

        List<AgentToolDefinition> tools = Arrays.asList(
                readTool("nq:a"), readTool("nq:b"), readTool("nq:c"));
        List<LlmChatResponse.ContentBlock> blocks = Arrays.asList(
                toolBlock("c1", "nq:a"),
                toolBlock("c2", "nq:b"),
                toolBlock("c3", "nq:c"));

        long start = System.currentTimeMillis();
        List<StepLoopService.ToolResult> results = stepLoopService.processToolUseBlocksParallel(
                blocks, tools, 101L, "run_serial", null, "agent_a", TraceContext.builder().traceId("t").tenantId(101L).build());
        long elapsed = System.currentTimeMillis() - start;

        assertThat(results).hasSize(3);
        // Serial = ≥ 3 × 50ms = 150ms (with margin)
        assertThat(elapsed).isGreaterThanOrEqualTo(140);
        // No parallel coords set on serial path
        assertThat(toolLoopService.parallelGroupIds).allMatch(g -> g == null);
        // All ran on the same calling thread (serial path)
        assertThat(toolLoopService.threadsUsed).hasSize(1);
    }

    // -------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------

    private AgentToolDefinition readTool(String name) {
        return AgentToolDefinition.builder()
                .name(name)
                .toolType("dsl_query")
                .sourceCode(name.replaceFirst("^nq:", ""))
                .requiresApproval(false)
                .riskLevel("L0")
                .build();
    }

    private LlmChatResponse.ContentBlock toolBlock(String id, String name) {
        return LlmChatResponse.ContentBlock.builder()
                .type("tool_use")
                .id(id)
                .name(name)
                .input(Map.of())
                .build();
    }

    /**
     * Lightweight stub: bypasses the real ToolLoopService dispatch logic. We
     * only care here about (a) which thread a call ran on, (b) whether the
     * MetaContext / StepContext leaked into worker threads correctly, and
     * (c) failure injection.
     */
    static class RecordingToolLoopService extends ToolLoopService {
        final ConcurrentHashMap<String, String> threadByCall = new ConcurrentHashMap<>();
        final java.util.Set<String> threadsUsed = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final List<Long> tenantIdsObserved = java.util.Collections.synchronizedList(new ArrayList<>());
        final List<String> parallelGroupIds = java.util.Collections.synchronizedList(new ArrayList<>());
        final List<Integer> parallelIndices = java.util.Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger callCount = new AtomicInteger();
        long sleepMs = 0;
        String failOn = null;
        boolean captureMetaContext = false;

        RecordingToolLoopService() {
            // All-null deps is safe ONLY because we override executeToolCall
            // and never invoke any other ToolLoopService method; the parent
            // constructor stores the references but does not deref them.
            // Adding new abstract dependencies upstream that ToolLoopService
            // actually uses in its constructor body would break this stub.
            super(null, null, null, null, null, null, null, new ObjectMapper(), null, null);
        }

        @Override
        public String executeToolCall(Long tenantId, String runPid, String taskPid, String agentCode,
                                       String toolName, Map<String, Object> input,
                                       List<AgentToolDefinition> tools, TraceContext traceCtx) {
            callCount.incrementAndGet();
            threadByCall.put(toolName, Thread.currentThread().getName());
            threadsUsed.add(Thread.currentThread().getName());
            if (captureMetaContext) {
                tenantIdsObserved.add(MetaContext.getCurrentTenantId());
                parallelGroupIds.add(StepContext.getParallelGroupId());
                parallelIndices.add(StepContext.getParallelIndex());
            }
            if (failOn != null && failOn.equals(toolName)) {
                throw new RuntimeException("synthetic failure for " + toolName);
            }
            if (sleepMs > 0) {
                try { Thread.sleep(sleepMs); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            return "ok:" + toolName;
        }
    }
}
