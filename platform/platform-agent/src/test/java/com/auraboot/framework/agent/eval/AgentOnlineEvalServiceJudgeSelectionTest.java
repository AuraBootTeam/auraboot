package com.auraboot.framework.agent.eval;

import com.auraboot.framework.agent.eval.AgentTurnQualityJudge.TurnSignals;
import com.auraboot.framework.agent.eval.AgentTurnQualityJudge.TurnVerdict;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentOnlineEvalServiceJudgeSelectionTest {

    @Test
    void llmModeRunsHeuristicComparisonAndReportsDisagreement() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AgentTurnQualityJudge heuristic = judge(
                "heuristic", new TurnVerdict("run-1", 1.0, true, "clean"));
        AgentTurnQualityJudge llm = judge(
                "llm", new TurnVerdict("run-1", 0.2, false, "ignored evidence"));
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(observation("hybrid", 1)));
        AgentOnlineEvalService service =
                new AgentOnlineEvalService(jdbc, List.of(heuristic, llm), "llm");

        var summary = service.sampleAndJudge(7L, 24, 20);

        assertThat(summary.judgeMode()).isEqualTo("llm");
        assertThat(summary.judgedTurns()).isEqualTo(1);
        assertThat(summary.judgeComparisonTurns()).isEqualTo(1);
        assertThat(summary.judgeConsistencyRate()).isZero();
        assertThat(summary.attribution().generationProblems()).isEqualTo(1);
        verify(llm).judge(any(TurnSignals.class));
        verify(heuristic).judge(any(TurnSignals.class));
    }

    @Test
    void keywordPathSkipsBothJudgesAndGenerationDenominator() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AgentTurnQualityJudge heuristic = judge(
                "heuristic", new TurnVerdict("run-1", 1.0, true, "clean"));
        AgentTurnQualityJudge llm = judge(
                "llm", new TurnVerdict("run-1", 1.0, true, "clean"));
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(observation("keyword", 1)));
        AgentOnlineEvalService service =
                new AgentOnlineEvalService(jdbc, List.of(heuristic, llm), "llm");

        var summary = service.sampleAndJudge(7L, 24, 20);

        assertThat(summary.sampledTurns()).isEqualTo(1);
        assertThat(summary.judgedTurns()).isZero();
        assertThat(summary.keywordPathTurns()).isEqualTo(1);
        assertThat(summary.attribution().configurationProblems()).isEqualTo(1);
        verify(heuristic, never()).judge(any(TurnSignals.class));
        verify(llm, never()).judge(any(TurnSignals.class));
    }

    private static AgentTurnQualityJudge judge(String mode, TurnVerdict verdict) {
        AgentTurnQualityJudge judge = mock(AgentTurnQualityJudge.class);
        when(judge.mode()).thenReturn(mode);
        when(judge.judge(any(TurnSignals.class))).thenReturn(verdict);
        return judge;
    }

    private static Map<String, Object> observation(String path, int resultCount) {
        return Map.of(
                "source_id", "run-1",
                "obs_agent_id", "support-agent",
                "observation_type", "activity",
                "severity", "info",
                "obs_title", "turn.completed: run-1",
                "detail", """
                        {"eventType":"turn.completed","input":"question","output":"answer",
                         "retrieval":{"path":"%s","resultCount":%d,"scores":[],"warnings":[]}}
                        """.formatted(path, resultCount));
    }
}
