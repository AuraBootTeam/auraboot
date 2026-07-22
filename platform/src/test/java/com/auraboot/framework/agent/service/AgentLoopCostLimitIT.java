package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-run cost ceiling is the answer to "how much can one runaway agent
 * spend", and it had no test. Its sibling — the deadline — did not either, and
 * writing that one found a real defect (a run with no deadline was killed by its
 * own deadline check). There was no reason to assume this half was fine.
 *
 * <p>Driven through a stub provider rather than a live one: the behaviour under
 * test is the loop's reaction to a cost number, and paying a vendor to produce
 * that number would make the test slow, non-deterministic and expensive without
 * testing anything more.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("A run that hits its cost ceiling stops")
class AgentLoopCostLimitIT extends BaseIntegrationTest {

    @Autowired
    private StepLoopService stepLoopService;

    @Autowired
    private DynamicDataMapper dynamicDataMapper;

    private final String runTag = UniqueIdGenerator.generate().substring(18);
    private final String agentCode = "cost-" + runTag;

    @AfterEach
    void cleanup() {
        dynamicDataMapper.deleteByQuery(
                "DELETE FROM ab_agent_run WHERE agent_id = #{params.agent}",
                Map.of("agent", agentCode));
    }

    private String seedRun() {
        String pid = UniqueIdGenerator.generate();
        Map<String, Object> run = new HashMap<>();
        run.put("pid", pid);
        run.put("tenant_id", getTestTenant().getId());
        run.put("agent_id", agentCode);
        run.put("task_id", "task-" + runTag);
        run.put("run_status", "running");
        run.put("started_at", LocalDateTime.now());
        run.put("created_at", LocalDateTime.now());
        run.put("updated_at", LocalDateTime.now());
        dynamicDataMapper.insert("ab_agent_run", run);
        return pid;
    }

    /**
     * Always answers with a tool_use block, so the loop never terminates of its
     * own accord — whatever stops it is the thing under test — and reports a
     * fixed cost per call.
     */
    private static class CostingProvider implements LlmProvider {
        private final double costPerCall;
        final AtomicInteger calls = new AtomicInteger();

        CostingProvider(double costPerCall) {
            this.costPerCall = costPerCall;
        }

        @Override
        public String getProviderCode() {
            return "stub-costing";
        }

        @Override
        public String getDisplayName() {
            return "Costing stub";
        }

        @Override
        public String getDefaultModel() {
            return "stub-model";
        }

        @Override
        public String getDefaultBaseUrl() {
            return "https://example.invalid";
        }

        @Override
        public boolean supportsTools() {
            return true;
        }

        @Override
        public double estimateCost(String model, int inputTokens, int outputTokens) {
            return costPerCall * calls.get();
        }

        @Override
        public LlmChatResponse chat(com.auraboot.framework.agent.dto.LlmChatRequest request,
                                    String apiKey, String baseUrl) {
            calls.incrementAndGet();
            LlmChatResponse.ContentBlock text = new LlmChatResponse.ContentBlock();
            text.setType("text");
            text.setText("still working");
            LlmChatResponse response = new LlmChatResponse();
            response.setContent(List.of(text));
            response.setStopReason("tool_use"); // never a natural stop
            response.setInputTokens(1000);
            response.setOutputTokens(1000);
            return response;
        }

        @Override
        public double estimateCost(String model, int inputTokens, int outputTokens,
                                   int cacheCreationTokens, int cacheReadTokens) {
            return costPerCall * calls.get();
        }
    }

    private Map<String, Object> agentDefWithCostLimit(double limit) {
        Map<String, Object> def = new HashMap<>();
        def.put("agent_code", agentCode);
        def.put("model", "stub-model");
        def.put("guardrails", "{\"maxCostPerRun\":" + limit + "}");
        return def;
    }

    private LlmProviderFactory.ProviderConfig stubConfig() {
        return LlmProviderFactory.ProviderConfig.builder()
                .apiKey("k").baseUrl("https://example.invalid").build();
    }

    @Test
    @DisplayName("the loop stops as soon as the spend passes the ceiling")
    void loopStopsOnceCostExceedsTheLimit() throws Exception {
        String runPid = seedRun();
        // One call costs 5.0 against a ceiling of 1.0, so the very first answer
        // is already over budget.
        CostingProvider provider = new CostingProvider(5.0);

        stepLoopService.executeAgentLoop(
                getTestTenant().getId(), runPid, "task-" + runTag, agentCode,
                "system", "user", List.of(), agentDefWithCostLimit(1.0), null,
                provider, stubConfig(), null, 8);

        assertThat(provider.calls.get())
                .as("an over-budget run must stop at once, not keep calling until max loops")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a run inside its budget is allowed to keep going")
    void loopContinuesWhileWithinBudget() throws Exception {
        String runPid = seedRun();
        // Cheap enough that eight iterations stay under the ceiling. Without this
        // control, a loop that stopped after every first call would satisfy the
        // assertion above while making the agent useless.
        CostingProvider provider = new CostingProvider(0.001);

        stepLoopService.executeAgentLoop(
                getTestTenant().getId(), runPid, "task-" + runTag, agentCode,
                "system", "user", List.of(), agentDefWithCostLimit(100.0), null,
                provider, stubConfig(), null, 8);

        assertThat(provider.calls.get())
                .as("a run inside its budget must not be cut short by the cost check")
                .isEqualTo(8);
    }
}
