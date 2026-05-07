package com.auraboot.framework.promotion.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit test for the pure-logic state machine. No Spring, no DB.
 */
class PromotionStateMachineTest {

    @ParameterizedTest(name = "{0} → {1} should be allowed")
    @CsvSource({
            "DRAFT, VALIDATED",
            "VALIDATED, DRAFT",
            "VALIDATED, VALIDATED",   // idempotent re-validate
            "VALIDATED, APPLIED",
            "VALIDATED, REJECTED",
            "VALIDATED, FAILED",
            "FAILED, DRAFT",
    })
    void allowedTransitions(String from, String to) {
        PromotionStatus f = PromotionStatus.valueOf(from);
        PromotionStatus t = PromotionStatus.valueOf(to);
        assertThat(PromotionStateMachine.canTransition(f, t)).isTrue();
    }

    @ParameterizedTest(name = "{0} → {1} must be blocked")
    @CsvSource({
            "DRAFT, APPLIED",        // must validate first
            "DRAFT, REJECTED",
            "DRAFT, FAILED",
            "DRAFT, DRAFT",          // self-loop other than VALIDATED is a no-op (rejected to surface dead writes)
            "APPLIED, DRAFT",        // terminal
            "APPLIED, VALIDATED",
            "APPLIED, APPLIED",
            "REJECTED, DRAFT",       // terminal — must create a new promotion
            "REJECTED, VALIDATED",
            "FAILED, APPLIED",       // FAILED must re-validate before reapply
            "FAILED, VALIDATED",
    })
    void blockedTransitions(String from, String to) {
        PromotionStatus f = PromotionStatus.valueOf(from);
        PromotionStatus t = PromotionStatus.valueOf(to);
        assertThat(PromotionStateMachine.canTransition(f, t)).isFalse();
    }

    @Test
    void nullArgumentsAreNeverAllowed() {
        assertThat(PromotionStateMachine.canTransition(null, PromotionStatus.DRAFT)).isFalse();
        assertThat(PromotionStateMachine.canTransition(PromotionStatus.DRAFT, null)).isFalse();
        assertThat(PromotionStateMachine.canTransition(null, null)).isFalse();
    }

    @Test
    void assertCanTransition_throwsOnIllegal_andNamesTheSourceState() {
        assertThatThrownBy(() ->
                PromotionStateMachine.assertCanTransition(PromotionStatus.APPLIED, PromotionStatus.DRAFT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APPLIED")
                .hasMessageContaining("DRAFT");
    }

    @Test
    void terminalStatusesReportThemselves() {
        assertThat(PromotionStatus.APPLIED.isTerminal()).isTrue();
        assertThat(PromotionStatus.REJECTED.isTerminal()).isTrue();
        assertThat(PromotionStatus.DRAFT.isTerminal()).isFalse();
        assertThat(PromotionStatus.VALIDATED.isTerminal()).isFalse();
        assertThat(PromotionStatus.FAILED.isTerminal()).isFalse();
    }
}
