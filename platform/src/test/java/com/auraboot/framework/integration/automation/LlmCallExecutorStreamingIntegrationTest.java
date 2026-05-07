package com.auraboot.framework.integration.automation;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.dto.LlmChunk;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.event.AutomationLlmChunkEvent;
import com.auraboot.framework.automation.event.AutomationRunStreamPublisher;
import com.auraboot.framework.automation.executor.impl.LlmCallExecutor;
import com.auraboot.framework.integration.BaseIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Integration test for {@link LlmCallExecutor} streaming path (E.1 Phase 1).
 *
 * <p>Three cases (per spec):
 * <ul>
 *   <li><b>Case A</b> — streaming impl produces the same {@code ${outputVariable}}
 *       value the legacy sync path produced (regression / equivalence)</li>
 *   <li><b>Case B</b> — at least one {@link AutomationLlmChunkEvent} fires
 *       through the side-channel during execution</li>
 *   <li><b>Case C</b> — slow consumer simulation: drop counter increments
 *       and the node still completes successfully (Q8)</li>
 * </ul>
 *
 * <p>The provider factory + provider are stubbed via {@code @MockitoBean} so
 * the test runs without external HTTP. The {@link AutomationRunStreamPublisher}
 * is the real bean — we want the bounded executor and counter machinery to
 * exercise.
 */
class LlmCallExecutorStreamingIntegrationTest extends BaseIntegrationTest {

    @MockitoBean
    private LlmProviderFactory llmProviderFactory;

    @Autowired
    private LlmCallExecutor executor;

    @Autowired
    private AutomationRunStreamPublisher streamPublisher;

    @Autowired
    private MeterRegistry meterRegistry;

    private void wireProviderConfig(LlmProvider providerStub) {
        lenient().when(llmProviderFactory.resolveProviderByModel(anyString())).thenReturn("anthropic");
        lenient().when(llmProviderFactory.resolveConfig(any(), anyString()))
                .thenReturn(LlmProviderFactory.ProviderConfig.builder()
                        .providerCode("anthropic")
                        .apiKey("sk-test")
                        .baseUrl("https://api.anthropic.com")
                        .defaultModel("claude-sonnet-4-6")
                        .maxTokens(4096)
                        .build());
        lenient().when(llmProviderFactory.getProvider("anthropic")).thenReturn(providerStub);
    }

    @Test
    void caseA_streamingProducesSameOutputVariableAsSync() {
        // Build a stub provider whose streamChat emits 3 deltas + terminal
        // aggregating to "Final-summary".
        LlmProvider providerStub = new LlmProvider() {
            @Override public String getProviderCode() { return "anthropic"; }
            @Override public String getDisplayName() { return "stub"; }
            @Override public boolean supportsTools() { return true; }
            @Override public LlmChatResponse chat(LlmChatRequest request, String apiKey, String baseUrl) {
                return buildResponse("Final-summary");
            }
            @Override public double estimateCost(String model, int in, int out) { return 0; }
            @Override public String getDefaultBaseUrl() { return "https://api.anthropic.com"; }
            @Override public String getDefaultModel() { return "claude-sonnet-4-6"; }
            @Override public Flux<LlmChunk> streamChat(LlmChatRequest request, String apiKey, String baseUrl) {
                return Flux.just(
                        LlmChunk.delta(0L, "Final"),
                        LlmChunk.delta(1L, "-"),
                        LlmChunk.delta(2L, "summary"),
                        LlmChunk.done(3L, buildResponse("Final-summary"))
                );
            }
        };
        wireProviderConfig(providerStub);

        AutomationAction action = AutomationAction.builder()
                .type("llm_call")
                .label("summarise-node")
                .sequence(1)
                .config(new HashMap<>(Map.of(
                        "model", "claude-sonnet-4-6",
                        "userPromptTemplate", "summarise: ${trigger.text}"
                )))
                .build();

        Map<String, Object> context = new HashMap<>();
        context.put("trigger.text", "hello world");
        context.put("runPid", "RUN-" + System.nanoTime());

        executor.execute(action, context);

        assertThat(context.get("llmOutput")).isEqualTo("Final-summary");
    }

    @Test
    void caseB_publishesAtLeastOneChunkEventDuringExecution() throws Exception {
        AtomicInteger observed = new AtomicInteger();
        String runPid = "RUN-" + System.nanoTime();
        AutomationRunStreamPublisher.Subscription sub = streamPublisher
                .subscribe(runPid, "publisher-node", (chunk, seq) -> observed.incrementAndGet());

        try {
            LlmProvider providerStub = new LlmProvider() {
                @Override public String getProviderCode() { return "anthropic"; }
                @Override public String getDisplayName() { return "stub"; }
                @Override public boolean supportsTools() { return true; }
                @Override public LlmChatResponse chat(LlmChatRequest req, String k, String u) {
                    return buildResponse("ok");
                }
                @Override public double estimateCost(String m, int i, int o) { return 0; }
                @Override public String getDefaultBaseUrl() { return ""; }
                @Override public String getDefaultModel() { return "claude-sonnet-4-6"; }
                @Override public Flux<LlmChunk> streamChat(LlmChatRequest req, String k, String u) {
                    return Flux.just(
                            LlmChunk.delta(0L, "o"),
                            LlmChunk.delta(1L, "k"),
                            LlmChunk.done(2L, buildResponse("ok"))
                    );
                }
            };
            wireProviderConfig(providerStub);

            AutomationAction action = AutomationAction.builder()
                    .type("llm_call")
                    .label("publisher-node")
                    .sequence(1)
                    .config(new HashMap<>(Map.of(
                            "model", "claude-sonnet-4-6",
                            "userPromptTemplate", "go"
                    )))
                    .build();

            Map<String, Object> ctx = new HashMap<>();
            ctx.put("runPid", runPid);
            executor.execute(action, ctx);

            // Async fan-out — give the bounded executor up to 2s to drain.
            long deadline = System.currentTimeMillis() + 2_000L;
            while (observed.get() < 1 && System.currentTimeMillis() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(observed.get()).isGreaterThanOrEqualTo(1);
        } finally {
            sub.unsubscribe();
        }
    }

    @Test
    void caseC_slowConsumerCausesDropCounterIncrementButNodeStillCompletes() {
        // Saturate the bounded executor's queue (capacity=256) by emitting
        // 600 deltas with a deliberately slow subscriber; some MUST be
        // dropped via DiscardOldestPolicy. The node still completes.
        String runPid = "RUN-" + System.nanoTime();
        AutomationRunStreamPublisher.Subscription sub = streamPublisher
                .subscribe(runPid, "slow-node", (chunk, seq) -> {
                    try { Thread.sleep(2); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                });

        double droppedBefore = meterRegistry.counter("aura_workflow_stream_chunk_dropped_total").count();

        try {
            LlmProvider providerStub = new LlmProvider() {
                @Override public String getProviderCode() { return "anthropic"; }
                @Override public String getDisplayName() { return "stub"; }
                @Override public boolean supportsTools() { return true; }
                @Override public LlmChatResponse chat(LlmChatRequest r, String k, String u) {
                    return buildResponse("done");
                }
                @Override public double estimateCost(String m, int i, int o) { return 0; }
                @Override public String getDefaultBaseUrl() { return ""; }
                @Override public String getDefaultModel() { return "claude-sonnet-4-6"; }
                @Override public Flux<LlmChunk> streamChat(LlmChatRequest r, String k, String u) {
                    Flux<LlmChunk> deltas = Flux.range(0, 600)
                            .map(i -> LlmChunk.delta(i, "x"));
                    return deltas.concatWith(Flux.just(LlmChunk.done(600L, buildResponse("done"))));
                }
            };
            wireProviderConfig(providerStub);

            AutomationAction action = AutomationAction.builder()
                    .type("llm_call")
                    .label("slow-node")
                    .sequence(1)
                    .config(new HashMap<>(Map.of(
                            "model", "claude-sonnet-4-6",
                            "userPromptTemplate", "stress"
                    )))
                    .build();
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("runPid", runPid);
            executor.execute(action, ctx);

            assertThat(ctx.get("llmOutput")).isEqualTo("done");

            // Allow async draining to settle.
            long deadline = System.currentTimeMillis() + 3_000L;
            double droppedAfter;
            do {
                droppedAfter = meterRegistry.counter("aura_workflow_stream_chunk_dropped_total").count();
                if (droppedAfter > droppedBefore) break;
                Thread.onSpinWait();
            } while (System.currentTimeMillis() < deadline);

            assertThat(droppedAfter).isGreaterThan(droppedBefore);
        } finally {
            sub.unsubscribe();
        }
    }

    private static LlmChatResponse buildResponse(String text) {
        return LlmChatResponse.builder()
                .stopReason("end_turn")
                .content(List.of(LlmChatResponse.ContentBlock.builder()
                        .type("text").text(text).build()))
                .inputTokens(10).outputTokens(5)
                .build();
    }
}
