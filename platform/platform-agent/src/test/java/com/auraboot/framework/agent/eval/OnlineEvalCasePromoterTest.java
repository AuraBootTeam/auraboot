package com.auraboot.framework.agent.eval;

import com.auraboot.framework.agent.entity.AgentEvalCase;
import com.auraboot.framework.agent.eval.AgentTurnQualityJudge.TurnVerdict;
import com.auraboot.framework.agent.mapper.AgentEvalCaseMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OnlineEvalCasePromoter} (CAP-02 flywheel closure).
 */
class OnlineEvalCasePromoterTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AgentEvalCaseMapper caseMapper = mock(AgentEvalCaseMapper.class);
    private final OnlineEvalCasePromoter promoter = new OnlineEvalCasePromoter(jdbc, caseMapper);

    private TurnVerdict hardFail(String runPid) {
        return new TurnVerdict(runPid, 0.0, false, "step failed: tool error");
    }

    @SuppressWarnings("unchecked")
    private void stubAgentCode(String code) {
        when(jdbc.queryForList(anyString(), eq(String.class), any(), any()))
                .thenReturn(code == null ? List.of() : List.of(code));
    }

    @Test
    void hardFailure_createsDedupedCandidateCase() {
        stubAgentCode("aurabot");
        when(caseMapper.selectCount(any())).thenReturn(0L);   // not yet captured
        when(caseMapper.insert(any(AgentEvalCase.class))).thenReturn(1);

        int promoted = promoter.promoteHardFailures(7L, List.of(hardFail("run-abc")), 20);

        assertThat(promoted).isEqualTo(1);
        ArgumentCaptor<AgentEvalCase> captor = ArgumentCaptor.forClass(AgentEvalCase.class);
        verify(caseMapper).insert(captor.capture());
        AgentEvalCase c = captor.getValue();
        assertThat(c.getTenantId()).isEqualTo(7L);
        assertThat(c.getAgentCode()).isEqualTo("aurabot");
        assertThat(c.getCaseId()).isEqualTo("online-run-abc");
        assertThat(c.getCategory()).isEqualTo(OnlineEvalCasePromoter.CANDIDATE_CATEGORY);
        assertThat(c.getExpectedToolCodes()).isEmpty();
        assertThat(c.getPluginSource()).isEqualTo("online-eval");
        assertThat(c.getPid()).isNotBlank();
        assertThat(c.getTaskDescription()).contains("run-abc");
    }

    @Test
    void alreadyCaptured_skipsInsert() {
        stubAgentCode("aurabot");
        when(caseMapper.selectCount(any())).thenReturn(1L);   // dedup: exists

        int promoted = promoter.promoteHardFailures(7L, List.of(hardFail("run-abc")), 20);

        assertThat(promoted).isZero();
        verify(caseMapper, never()).insert(any(AgentEvalCase.class));
    }

    @Test
    void nonHardFailures_areNotPromoted() {
        // healthy turn + unhealthy-but-not-hard (score > 0) are both skipped
        int promoted = promoter.promoteHardFailures(7L, List.of(
                new TurnVerdict("run-healthy", 1.0, true, "ok"),
                new TurnVerdict("run-ambiguous", 0.4, false, "low but not a hard fail")), 20);

        assertThat(promoted).isZero();
        verify(caseMapper, never()).insert(any(AgentEvalCase.class));
    }

    @Test
    void unresolvableAgentCode_isSkipped() {
        stubAgentCode(null);   // no obs_agent_id for the run

        int promoted = promoter.promoteHardFailures(7L, List.of(hardFail("run-orphan")), 20);

        assertThat(promoted).isZero();
        verify(caseMapper, never()).insert(any(AgentEvalCase.class));
    }

    @Test
    void maxPromotions_capsCandidatesCreated() {
        stubAgentCode("aurabot");
        when(caseMapper.selectCount(any())).thenReturn(0L);
        when(caseMapper.insert(any(AgentEvalCase.class))).thenReturn(1);

        int promoted = promoter.promoteHardFailures(7L, List.of(
                hardFail("run-1"), hardFail("run-2"), hardFail("run-3")), 2);

        assertThat(promoted).isEqualTo(2);
        verify(caseMapper, org.mockito.Mockito.times(2)).insert(any(AgentEvalCase.class));
    }
}
