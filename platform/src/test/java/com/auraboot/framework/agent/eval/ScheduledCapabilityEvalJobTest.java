package com.auraboot.framework.agent.eval;

import com.auraboot.framework.agent.dto.CapabilityEvalCase;
import com.auraboot.framework.agent.entity.AbCapabilityEvalRun;
import com.auraboot.framework.agent.mapper.AbCapabilityEvalRunMapper;
import com.auraboot.framework.agent.service.AgentObservationService;
import com.auraboot.framework.agent.service.CapabilityEvalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the scheduled eval loop wiring: the enabled flag is honored, and a
 * gate-detected regression emits exactly one observation event (healthy emits none).
 * Mockito only — no DB / Spring / LLM.
 */
class ScheduledCapabilityEvalJobTest {

    private final CapabilityEvalService evalService = mock(CapabilityEvalService.class);
    private final AbCapabilityEvalRunMapper evalRunMapper = mock(AbCapabilityEvalRunMapper.class);
    private final AgentObservationService observationService = mock(AgentObservationService.class);

    private ScheduledCapabilityEvalJob job;

    @BeforeEach
    void setup() {
        job = new ScheduledCapabilityEvalJob(evalService, evalRunMapper, observationService);
        ReflectionTestUtils.setField(job, "tenantId", 1L);
        ReflectionTestUtils.setField(job, "mode", "keyword");
        ReflectionTestUtils.setField(job, "maxCases", 20);
        ReflectionTestUtils.setField(job, "minToolAccuracy", 0.70);
        ReflectionTestUtils.setField(job, "minParamCompletion", 0.60);
        ReflectionTestUtils.setField(job, "minSafety", 0.90);
        ReflectionTestUtils.setField(job, "minComposability", 0.50);
        ReflectionTestUtils.setField(job, "maxHallucination", 0.10);
        ReflectionTestUtils.setField(job, "regressionTolerance", 0.05);
        ReflectionTestUtils.setField(job, "baselineWindow", 5);
        // generateEvalCases empty → runOnce uses the 2-arg evaluateToolSelection path
        when(evalService.generateEvalCases(anyLong(), any(), anyInt())).thenReturn(List.<CapabilityEvalCase>of());
        when(evalService.evaluateToolSelection(anyLong(), anyString())).thenReturn(Map.of("evalMode", "keyword"));
    }

    private static AbCapabilityEvalRun run(String pid, int secsAgo, double toolAcc, double halluc) {
        AbCapabilityEvalRun r = new AbCapabilityEvalRun();
        r.setPid(pid);
        r.setTenantId(1L);
        r.setRunAt(Instant.parse("2026-06-12T00:00:00Z").minusSeconds(secsAgo));
        r.setToolSelectionAccuracy(toolAcc);
        r.setParameterCompletionRate(0.85);
        r.setSafetyComplianceRate(0.99);
        r.setComposabilityScore(0.80);
        r.setHallucinationRate(halluc);
        return r;
    }

    @Test
    void disabled_isNoOp() {
        ReflectionTestUtils.setField(job, "enabled", false);
        job.runScheduled();
        verifyNoInteractions(evalService);
        verifyNoInteractions(observationService);
    }

    @Test
    void enabledWithRegression_emitsOneObservationEvent() {
        ReflectionTestUtils.setField(job, "enabled", true);
        // latest run is below the tool-accuracy floor → gate flags it.
        when(evalRunMapper.selectList(any())).thenReturn(List.of(
                run("latest", 0, 0.50, 0.02),     // 0.50 < 0.70 floor
                run("h1", 100, 0.92, 0.02),
                run("h2", 200, 0.93, 0.02)));

        job.runScheduled();

        verify(evalService, times(1)).evaluateToolSelection(eq(1L), eq("keyword"));
        verify(observationService, times(1)).publish(eq(1L), eq("capability_eval.regression"),
                anyString(), anyString(), any(), any());
    }

    @Test
    void enabledHealthy_emitsNoEvent() {
        ReflectionTestUtils.setField(job, "enabled", true);
        when(evalRunMapper.selectList(any())).thenReturn(List.of(
                run("latest", 0, 0.92, 0.02),
                run("h1", 100, 0.93, 0.02)));

        job.runScheduled();

        verify(evalService, times(1)).evaluateToolSelection(eq(1L), eq("keyword"));
        verify(observationService, never()).publish(anyLong(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void includeArchetypeCases_callsLoadRegisteredCases() {
        // Set includeArchetypeCases=true so the Task-8-changed branch is exercised.
        ReflectionTestUtils.setField(job, "includeArchetypeCases", true);
        // generateEvalCases returns empty (per @BeforeEach), so cases list is populated
        // solely by loadRegisteredCases; making it non-empty forces the 3-arg path.
        CapabilityEvalCase stubbedCase = CapabilityEvalCase.builder()
                .taskDescription("what tools list orders?")
                .expectedToolCodes(List.of("order:list"))
                .build();
        when(evalService.loadRegisteredCases(1L)).thenReturn(List.of(stubbedCase));
        when(evalService.evaluateToolSelection(eq(1L), eq("keyword"), any()))
                .thenReturn(Map.of("evalMode", "keyword", "toolSelectionAccuracy", 0.92));

        job.runOnce(1L);

        // Verify the swapped wiring: loadRegisteredCases is called (not AgentArchetypeEvalCases.all())
        verify(evalService, times(1)).loadRegisteredCases(1L);
        // Verify the cases flowed into the 3-arg overload (non-empty case list)
        verify(evalService, times(1)).evaluateToolSelection(eq(1L), eq("keyword"), any());
        // The 2-arg overload must NOT have been called (cases were non-empty)
        verify(evalService, never()).evaluateToolSelection(eq(1L), eq("keyword"));
    }
}
