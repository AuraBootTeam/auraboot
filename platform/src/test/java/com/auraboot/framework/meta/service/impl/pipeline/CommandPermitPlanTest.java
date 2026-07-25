package com.auraboot.framework.meta.service.impl.pipeline;

import com.auraboot.framework.meta.service.impl.pipeline.CommandPermitPlan.Decision;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPermitPlan.PhaseDecision;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPermitPlan.ScopeGrade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deny-overrides combination algebra (D2) and the plan carrier.
 *
 * <p>These are the rules the whole "decide once, execute everywhere else" contract rests on, so they
 * are pinned before any phase wires into them: a single refusal anywhere must sink the plan, and an
 * abstention must never read as a permit.</p>
 */
class CommandPermitPlanTest {

    private CommandPermitPlan combine(PhaseDecision... decisions) {
        return CommandPermitPlan.fromPhaseDecisions(List.of(decisions), null, null, null);
    }

    @Test
    @DisplayName("every phase permits -> PERMIT, no reason")
    void permitWhenAllPhasesPermit() {
        CommandPermitPlan plan = combine(PhaseDecision.permit("authz"), PhaseDecision.permit("scope"));

        assertThat(plan.isPermitted()).isTrue();
        assertThat(plan.decision()).isEqualTo(Decision.PERMIT);
        assertThat(plan.reasonCode()).isNull();
        assertThat(plan.deniedByPhase()).isNull();
    }

    @Test
    @DisplayName("any phase denies -> DENY, carrying that phase's reason")
    void denyWhenAnyPhaseDenies() {
        CommandPermitPlan plan = combine(PhaseDecision.permit("authz"), PhaseDecision.deny("scope_violation", "scope"));

        assertThat(plan.isDenied()).isTrue();
        assertThat(plan.isPermitted()).isFalse();
        assertThat(plan.reasonCode()).isEqualTo("scope_violation");
        assertThat(plan.deniedByPhase()).isEqualTo("scope");
    }

    /**
     * The load-bearing rule: a refusal overrides a grant no matter where it sits. If this regresses
     * to "last decision wins" or "a permit can reopen", a phase that says no gets silently ignored.
     */
    @Test
    @DisplayName("a deny overrides a permit regardless of order")
    void denyOverridesPermitRegardlessOfOrder() {
        assertThat(combine(PhaseDecision.deny("x", "authz"), PhaseDecision.permit("scope")).isDenied())
                .as("deny first")
                .isTrue();
        assertThat(combine(PhaseDecision.permit("scope"), PhaseDecision.deny("x", "authz")).isDenied())
                .as("permit first, deny later still overrides")
                .isTrue();
    }

    @Test
    @DisplayName("the earliest deny owns the reason")
    void earliestDenyOwnsTheReason() {
        CommandPermitPlan plan = combine(
                PhaseDecision.deny("first_reason", "authz"),
                PhaseDecision.deny("second_reason", "scope"));

        assertThat(plan.reasonCode()).isEqualTo("first_reason");
        assertThat(plan.deniedByPhase()).isEqualTo("authz");
    }

    @Test
    @DisplayName("all phases abstain -> ABSTAIN, and ABSTAIN is not a permit")
    void abstainWhenAllAbstain() {
        CommandPermitPlan plan = combine(PhaseDecision.abstain("authz"), PhaseDecision.abstain("scope"));

        assertThat(plan.decision()).isEqualTo(Decision.ABSTAIN);
        assertThat(plan.isPermitted()).as("abstention must never read as a permit").isFalse();
        assertThat(plan.isDenied()).isFalse();
    }

    @Test
    @DisplayName("no phases at all -> ABSTAIN")
    void abstainWhenNoPhases() {
        CommandPermitPlan plan = CommandPermitPlan.fromPhaseDecisions(List.of(), null, null, null);

        assertThat(plan.decision()).isEqualTo(Decision.ABSTAIN);
        assertThat(plan.isPermitted()).isFalse();
    }

    @Test
    @DisplayName("the execution fields (aggregate, scope, version) are carried onto the plan")
    void executionFieldsAreCarried() {
        CommandPermitPlan plan = CommandPermitPlan.fromPhaseDecisions(
                List.of(PhaseDecision.permit("authz")), "AGG-1", ScopeGrade.SELF, 7L);

        assertThat(plan.aggregateId()).isEqualTo("AGG-1");
        assertThat(plan.scope()).isEqualTo(ScopeGrade.SELF);
        assertThat(plan.expectedVersion()).isEqualTo(7L);
    }
}
