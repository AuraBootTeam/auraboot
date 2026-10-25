package com.auraboot.framework.agent.eval;

import com.auraboot.framework.agent.eval.AgentTurnQualityJudge.TurnSignals;
import com.auraboot.framework.agent.eval.AgentTurnQualityJudge.TurnVerdict;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentOnlineEvalServiceTranscriptTest {

    @Test
    void llmJudgeReceivesPersistedInboundAndOutboundTranscript() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CapturingJudge judge = new CapturingJudge();
        AgentOnlineEvalService service =
                new AgentOnlineEvalService(jdbc, judge, new ObjectMapper());

        Map<String, Object> observation = Map.of(
                "source_id", "turn-1",
                "obs_agent_id", "aurabot",
                "observation_type", "turn_completed",
                "severity", "info",
                "obs_title", "turn.completed",
                "detail", "{\"inboundMessageId\":42}");
        when(jdbc.queryForList(
                contains("FROM ab_agent_observation"), eq(7L), eq(24)))
                .thenReturn(List.of(observation));
        when(jdbc.queryForList(
                contains("tenant_id = ? AND id = ?"), eq(7L), eq(42L)))
                .thenReturn(List.of(Map.of("content", "What is order ORD-42's status?")));
        when(jdbc.queryForList(
                contains("client_msg_id = ?"), eq(7L), eq("out-turn-1")))
                .thenReturn(List.of(Map.of("content", "No matching order was found.")));

        var summary = service.sampleAndJudge(7L, 24, 10);

        assertThat(summary.sampledTurns()).isEqualTo(1);
        assertThat(judge.seen.narrative())
                .anyMatch(line -> line.contains("User request")
                        && line.contains("ORD-42"))
                .anyMatch(line -> line.contains("Agent response")
                        && line.contains("No matching order"));
        assertThat(judge.seen.completed()).isTrue();
    }

    private static final class CapturingJudge implements AgentTurnQualityJudge {
        private TurnSignals seen;

        @Override
        public TurnVerdict judge(TurnSignals signals) {
            seen = signals;
            return new TurnVerdict(signals.runPid(), 1.0, true, "captured");
        }

        @Override
        public String mode() {
            return "llm";
        }
    }
}
