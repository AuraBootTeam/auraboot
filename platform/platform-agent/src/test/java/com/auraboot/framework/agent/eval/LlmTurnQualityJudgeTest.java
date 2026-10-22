package com.auraboot.framework.agent.eval;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * The LLM judge exists to catch the turn that <em>completes cleanly and is still wrong</em>.
 * These tests pin the two properties that make it trustworthy: it grades the narrative, and
 * it never reports health it did not measure.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LLM turn-quality judge: grades the narrative, fails closed")
class LlmTurnQualityJudgeTest {

    @Mock private LlmProviderFactory llmProviderFactory;
    @Mock private LlmProvider provider;

    private LlmTurnQualityJudge judge;

    private static final Long TENANT = 1L;

    @BeforeEach
    void setUp() {
        judge = new LlmTurnQualityJudge(llmProviderFactory, new ObjectMapper());
        ReflectionTestUtils.setField(judge, "tenantId", TENANT);
    }

    private void providerReplies(String text) throws Exception {
        LlmProviderFactory.ProviderConfig cfg = new LlmProviderFactory.ProviderConfig();
        cfg.setProviderCode("provider-under-test");
        cfg.setDefaultModel("model-under-test");
        cfg.setApiKey("test-key-not-real");
        cfg.setBaseUrl("https://example.invalid");
        lenient().when(llmProviderFactory.resolveConfig(any(), any())).thenReturn(cfg);
        lenient().when(llmProviderFactory.getProvider(anyString())).thenReturn(provider);

        LlmChatResponse.ContentBlock block = new LlmChatResponse.ContentBlock();
        block.setType("text");
        block.setText(text);
        LlmChatResponse response = new LlmChatResponse();
        response.setContent(List.of(block));
        lenient().when(provider.chat(any(LlmChatRequest.class), anyString(), anyString()))
                .thenReturn(response);
    }

    private static AgentTurnQualityJudge.TurnSignals turnWithNarrative() {
        return new AgentTurnQualityJudge.TurnSignals(
                "run-1", "agent-a", 3, true, false, 0, false,
                List.of("[run_started] user asked for last quarter's revenue",
                        "[tool_called] chat_bi grouped by product line",
                        "[run_completed] answered with figures from the query result"));
    }

    @Test
    @DisplayName("a good turn is graded from its narrative")
    void goodTurnScoresHealthy() throws Exception {
        providerReplies("{\"score\": 0.9, \"healthy\": true, \"reason\": \"grounded in the query result\"}");

        var v = judge.judge(turnWithNarrative());

        assertThat(v.score()).isEqualTo(0.9);
        assertThat(v.healthy()).isTrue();
        assertThat(v.reason()).contains("grounded");
    }

    /**
     * The case the heuristic judge structurally cannot see: nothing failed, the run
     * completed, and the answer was still fabricated.
     */
    @Test
    @DisplayName("a clean-but-fabricated turn is caught even though nothing failed")
    void cleanButFabricatedTurnIsCaught() throws Exception {
        providerReplies("{\"score\": 0.2, \"healthy\": false, \"reason\": \"cited an order id absent from the trace\"}");

        var signals = turnWithNarrative();
        var v = judge.judge(signals);

        assertThat(signals.failed()).isFalse();          // the counters say this turn was fine…
        assertThat(signals.completed()).isTrue();
        assertThat(v.healthy()).isFalse();               // …the judge disagrees, on content
        assertThat(v.score()).isEqualTo(0.2);
    }

    @Test
    @DisplayName("a lenient reply cannot mark a low-scoring turn healthy")
    void lowScoreCannotBeHealthy() throws Exception {
        providerReplies("{\"score\": 0.1, \"healthy\": true, \"reason\": \"looks fine to me\"}");

        assertThat(judge.judge(turnWithNarrative()).healthy())
                .as("score below the 0.6 bar must not be reported healthy regardless of the flag")
                .isFalse();
    }

    @Test
    @DisplayName("no configured provider is explicitly skipped, never counted as generation failure")
    void missingProviderIsSkipped() {
        when(llmProviderFactory.resolveConfig(any(), any())).thenReturn(null);

        var v = judge.judge(turnWithNarrative());

        assertThat(v.healthy()).isFalse();
        assertThat(v.judged()).isFalse();
        assertThat(v.reason()).contains("no LLM provider configured");
    }

    @Test
    @DisplayName("a provider error means not judged, never healthy")
    void providerErrorFailsClosed() throws Exception {
        LlmProviderFactory.ProviderConfig cfg = new LlmProviderFactory.ProviderConfig();
        cfg.setProviderCode("provider-under-test");
        cfg.setDefaultModel("model-under-test");
        cfg.setApiKey("test-key-not-real");
        cfg.setBaseUrl("https://example.invalid");
        when(llmProviderFactory.resolveConfig(any(), any())).thenReturn(cfg);
        when(llmProviderFactory.getProvider(anyString())).thenReturn(provider);
        when(provider.chat(any(LlmChatRequest.class), anyString(), anyString()))
                .thenThrow(new IllegalStateException("upstream 500"));

        var v = judge.judge(turnWithNarrative());

        assertThat(v.healthy()).isFalse();
        assertThat(v.judged()).isFalse();
        assertThat(v.reason()).contains("judge unavailable");
    }

    @Test
    @DisplayName("an unparseable reply means not judged, never healthy")
    void unparseableReplyFailsClosed() throws Exception {
        providerReplies("I think the run went well overall!");

        var v = judge.judge(turnWithNarrative());

        assertThat(v.healthy()).isFalse();
        assertThat(v.score()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("a turn with no narrative is not judged rather than assumed good")
    void emptyNarrativeIsNotJudged() {
        var signals = new AgentTurnQualityJudge.TurnSignals(
                "run-2", "agent-a", 2, true, false, 0, false, List.of());

        var v = judge.judge(signals);

        assertThat(v.healthy()).isFalse();
        assertThat(v.judged()).isFalse();
        assertThat(v.reason()).contains("no turn narrative");
    }

    @Test
    @DisplayName("mode is reported as llm so summaries say which judge graded")
    void modeIsLlm() {
        assertThat(judge.mode()).isEqualTo("llm");
    }
}
