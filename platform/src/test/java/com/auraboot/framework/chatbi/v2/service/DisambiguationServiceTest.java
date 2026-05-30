package com.auraboot.framework.chatbi.v2.service;

import com.auraboot.framework.chatbi.v2.entity.ChatBiDisambiguationLog;
import com.auraboot.framework.chatbi.v2.mapper.ChatBiDisambiguationLogMapper;
import com.auraboot.framework.chatbi.v2.provider.Disambiguation;
import com.auraboot.framework.chatbi.v2.provider.IntentResult;
import com.auraboot.framework.chatbi.v2.provider.LlmUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
 * Exercises the trigger-rule semantics from PRD 17 §7.3 and verifies that
 * persistence happens exactly when a prompt is surfaced.
 */
class DisambiguationServiceTest {

    private ChatBiDisambiguationLogMapper mapper;
    private DisambiguationService service;

    @BeforeEach
    void setup() {
        mapper = mock(ChatBiDisambiguationLogMapper.class);
        service = new DisambiguationService(mapper);
    }

    private static IntentResult intent(double confidence, Disambiguation d) {
        return new IntentResult(
                Collections.emptyList(),
                confidence,
                d != null,
                d,
                Collections.emptyList(),
                LlmUsage.zero());
    }

    private static Disambiguation prompt(double... scores) {
        List<Disambiguation.Candidate> cs = java.util.stream.IntStream.range(0, scores.length)
                .mapToObj(i -> new Disambiguation.Candidate(
                        "METRIC", "code_" + i, "label_" + i, scores[i]))
                .toList();
        return new Disambiguation("销售额", cs);
    }

    // -- rule 1: top1 < 0.5 → LOW_CONFIDENCE ------------------------------

    @Test
    void lowConfidenceBelowFloorAlwaysPromptsAndPersists() {
        Disambiguation d = prompt(0.42, 0.30);
        DisambiguationService.Verdict v =
                service.evaluate(intent(0.42, d), 1L, "ANS_PID", "销售额");

        assertThat(v).isInstanceOf(DisambiguationService.Verdict.PromptUser.class);
        DisambiguationService.Verdict.PromptUser p = (DisambiguationService.Verdict.PromptUser) v;
        assertThat(p.reason()).isEqualTo(DisambiguationService.REASON_LOW_CONFIDENCE);
        assertThat(p.disambiguation()).isSameAs(d);
        assertThat(p.logPid()).hasSize(26); // ULID

        verify(mapper, times(1)).insert(any(ChatBiDisambiguationLog.class));
    }

    @Test
    void lowConfidenceWithoutCandidatesSynthesisesEmptyPrompt() {
        DisambiguationService.Verdict v =
                service.evaluate(intent(0.20, null), 1L, "ANS_PID", "玄学指标");

        assertThat(v).isInstanceOf(DisambiguationService.Verdict.PromptUser.class);
        DisambiguationService.Verdict.PromptUser p = (DisambiguationService.Verdict.PromptUser) v;
        assertThat(p.disambiguation().ambiguousTerm()).isEqualTo("玄学指标");
        assertThat(p.disambiguation().candidates()).isEmpty();
        assertThat(p.reason()).isEqualTo(DisambiguationService.REASON_LOW_CONFIDENCE);
        verify(mapper, times(1)).insert(any(ChatBiDisambiguationLog.class));
    }

    // -- rule 2: top1 - top2 < 0.15 AND top2 > 0.5 → AMBIGUOUS ------------

    @Test
    void closeRunnerUpAboveFloorSurfacesAmbiguousPrompt() {
        // 0.62 vs 0.55 = 0.07 < 0.15 margin, top2 above 0.5 floor
        Disambiguation d = prompt(0.62, 0.55);
        DisambiguationService.Verdict v =
                service.evaluate(intent(0.62, d), 1L, "ANS_PID", "客单价");

        assertThat(v).isInstanceOf(DisambiguationService.Verdict.PromptUser.class);
        DisambiguationService.Verdict.PromptUser p = (DisambiguationService.Verdict.PromptUser) v;
        assertThat(p.reason()).isEqualTo(DisambiguationService.REASON_AMBIGUOUS);
        verify(mapper, times(1)).insert(any(ChatBiDisambiguationLog.class));
    }

    @Test
    void runnerUpBelowFloorDoesNotTriggerAmbiguity() {
        // 0.62 vs 0.48 = 0.14 margin but top2 below 0.5 floor → top1 wins
        Disambiguation d = prompt(0.62, 0.48);
        DisambiguationService.Verdict v =
                service.evaluate(intent(0.62, d), 1L, "ANS_PID", "X");

        assertThat(v).isInstanceOf(DisambiguationService.Verdict.UseTop1.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void wideGapBetweenTop1AndTop2DoesNotTrigger() {
        // 0.90 vs 0.55 = 0.35 margin > 0.15 → top1 wins despite both above floor
        Disambiguation d = prompt(0.90, 0.55);
        DisambiguationService.Verdict v =
                service.evaluate(intent(0.90, d), 1L, "ANS_PID", "确定指标");

        assertThat(v).isInstanceOf(DisambiguationService.Verdict.UseTop1.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void marginExactlyAtThresholdIsTreatedAsConfident() {
        // 0.80 vs 0.65 = 0.15 exact margin — NOT < 0.15 → use top1
        Disambiguation d = prompt(0.80, 0.65);
        DisambiguationService.Verdict v =
                service.evaluate(intent(0.80, d), 1L, "ANS_PID", "X");

        assertThat(v).isInstanceOf(DisambiguationService.Verdict.UseTop1.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void singleCandidateNeverTriggersAmbiguity() {
        // High confidence + single candidate → top1 wins even with a Disambiguation payload
        Disambiguation d = prompt(0.95);
        DisambiguationService.Verdict v =
                service.evaluate(intent(0.95, d), 1L, "ANS_PID", "X");

        assertThat(v).isInstanceOf(DisambiguationService.Verdict.UseTop1.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void candidatesAreSortedBeforeTop2Check() {
        // Out of order — service must sort by score before comparing
        Disambiguation d = prompt(0.55, 0.62);
        DisambiguationService.Verdict v =
                service.evaluate(intent(0.62, d), 1L, "ANS_PID", "X");

        assertThat(v).isInstanceOf(DisambiguationService.Verdict.PromptUser.class);
        assertThat(((DisambiguationService.Verdict.PromptUser) v).reason())
                .isEqualTo(DisambiguationService.REASON_AMBIGUOUS);
    }

    // -- rule 3: high confidence → use top1 -------------------------------

    @Test
    void highConfidenceWithNoDisambiguationUsesTop1() {
        DisambiguationService.Verdict v =
                service.evaluate(intent(0.92, null), 1L, "ANS_PID", "总销售额");
        assertThat(v).isInstanceOf(DisambiguationService.Verdict.UseTop1.class);
        verifyNoInteractions(mapper);
    }

    // -- persistence payload integrity ------------------------------------

    @Test
    void persistedRowCarriesAllRequiredFields() {
        Disambiguation d = prompt(0.40, 0.20);
        service.evaluate(intent(0.40, d), 7L, "ANS_PID_XYZ", "销售额");

        ArgumentCaptor<ChatBiDisambiguationLog> captor =
                ArgumentCaptor.forClass(ChatBiDisambiguationLog.class);
        verify(mapper).insert(captor.capture());

        ChatBiDisambiguationLog row = captor.getValue();
        assertThat(row.getPid()).hasSize(26);
        assertThat(row.getTenantId()).isEqualTo(7L);
        assertThat(row.getAnswerPid()).isEqualTo("ANS_PID_XYZ");
        assertThat(row.getAmbiguousTerm()).isEqualTo("销售额");
        assertThat(row.getTriggerReason())
                .isEqualTo(DisambiguationService.REASON_LOW_CONFIDENCE);
        assertThat(row.getCandidatesJson())
                .contains("\"code\":\"code_0\"")
                .contains("\"score\":0.4");
    }

    @Test
    void ambiguousTermTruncatedAt256Chars() {
        String huge = "A".repeat(500);
        Disambiguation d = new Disambiguation(huge,
                List.of(new Disambiguation.Candidate("METRIC", "x", "label", 0.40)));
        service.evaluate(intent(0.40, d), 1L, "ANS", huge);

        ArgumentCaptor<ChatBiDisambiguationLog> captor =
                ArgumentCaptor.forClass(ChatBiDisambiguationLog.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getAmbiguousTerm()).hasSize(256);
    }

    // -- recordChoice -----------------------------------------------------

    @Test
    void recordChoiceDelegatesAndReportsRowsAffected() {
        when(mapper.recordChoice(1L, "DPID", "code_0")).thenReturn(1);
        assertThat(service.recordChoice(1L, "DPID", "code_0")).isTrue();
        verify(mapper, times(1)).recordChoice(1L, "DPID", "code_0");
    }

    @Test
    void recordChoiceIdempotentReturnsFalseOnSecondCall() {
        when(mapper.recordChoice(1L, "DPID", "code_0")).thenReturn(0);
        assertThat(service.recordChoice(1L, "DPID", "code_0")).isFalse();
    }

    // -- findByPid passthrough --------------------------------------------

    @Test
    void findByPidWrapsMapperResult() {
        ChatBiDisambiguationLog row = new ChatBiDisambiguationLog();
        row.setPid("X");
        when(mapper.findByPid(1L, "X")).thenReturn(row);

        assertThat(service.findByPid(1L, "X")).isPresent();
        assertThat(service.findByPid(1L, "Y")).isEmpty();
    }

    @Test
    void evaluateRejectsNullArgs() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.evaluate(null, 1L, "X", "Y"))
                .isInstanceOf(NullPointerException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.evaluate(intent(0.9, null), null, "X", "Y"))
                .isInstanceOf(NullPointerException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.evaluate(intent(0.9, null), 1L, null, "Y"))
                .isInstanceOf(NullPointerException.class);
    }

    @SuppressWarnings("unused")
    private void unusedAnyVerify() {
        verify(mapper, never()).insert(any(ChatBiDisambiguationLog.class));
        verify(mapper, never()).recordChoice(anyLong(), anyString(), anyString());
        verify(mapper, never()).findByPid(eq(1L), anyString());
    }
}
