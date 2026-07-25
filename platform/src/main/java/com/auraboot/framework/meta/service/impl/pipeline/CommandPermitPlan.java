package com.auraboot.framework.meta.service.impl.pipeline;

import java.util.List;

/**
 * The pipeline-wide authorization decision, aggregated across the ordered command phases into one
 * plan the data layer executes <em>without re-deciding</em>.
 *
 * <p>Where {@link CommandAuthorizationVerdict} is one phase's finding (the RBAC gate at
 * {@code @Order(200)}), the PermitPlan is the whole boundary's decision. Each phase contributes a
 * {@link PhaseDecision}; they combine <strong>deny-overrides</strong> — any {@code DENY} wins (D2).
 * The plan additionally carries what the data layer needs to carry out that single decision without
 * asking again: the aggregate root derived writes inherit (D4), the row-scope grade (D3), and the
 * record version the decision was made against — the optimistic TOCTOU guard (D5).</p>
 *
 * <p>This is the "decide once at the boundary, execute everywhere else" contract in one object.
 * Once a plan exists, the data layer never re-runs an authorization decision; it filters by the
 * plan's scope and asserts the plan's version, and that is all. See the authorization architecture
 * §11.15 (owner-settled decisions D1–D6) and §11.10 (shadow → enforce migration).</p>
 *
 * <p><strong>Phase-1 Shadow.</strong> Nothing constructs or consumes a full plan yet — this is the
 * carrier and the combination algebra, unit-proven, that the phase wiring (next slice) and the data
 * layer (the slice after) build on. Introducing it changes no behaviour.</p>
 */
public record CommandPermitPlan(
        Decision decision,
        String reasonCode,
        String deniedByPhase,
        String aggregateId,
        ScopeGrade scope,
        Long expectedVersion) {

    /**
     * The combined boundary outcome.
     *
     * <p>{@code ABSTAIN} is a real state, not a permit: a command that declares nothing has
     * authorized nothing (the ~200 undeclared-permission commands), and that must stay
     * distinguishable — treating abstention as approval is exactly the silent write-oracle this
     * whole line of work removes.</p>
     */
    public enum Decision { PERMIT, DENY, ABSTAIN }

    /**
     * Row-level scope grade (D3). {@code SELF} filters execution to the caller's own rows; {@code ALL}
     * applies no row filter. The predicate itself (owner column, current user) is resolved by the
     * data layer at execution — the plan carries only the grade.
     */
    public enum ScopeGrade { SELF, ALL }

    /** One phase's authorization finding, before combination. */
    public record PhaseDecision(Decision decision, String reasonCode, String phaseName) {

        public static PhaseDecision permit(String phaseName) {
            return new PhaseDecision(Decision.PERMIT, null, phaseName);
        }

        public static PhaseDecision deny(String reasonCode, String phaseName) {
            return new PhaseDecision(Decision.DENY, reasonCode, phaseName);
        }

        public static PhaseDecision abstain(String phaseName) {
            return new PhaseDecision(Decision.ABSTAIN, null, phaseName);
        }
    }

    public boolean isPermitted() {
        return decision == Decision.PERMIT;
    }

    public boolean isDenied() {
        return decision == Decision.DENY;
    }

    /**
     * Assemble the plan from the phases' decisions plus the execution fields.
     *
     * <p>Combination is <strong>deny-overrides</strong> (D2): the first phase to {@code DENY} wins
     * the whole plan — earliest, because the first stage to refuse owns the reason the caller should
     * see. With no denial, any {@code PERMIT} carries the plan; if every phase abstained (or there
     * were none), the plan {@code ABSTAIN}s.</p>
     */
    public static CommandPermitPlan fromPhaseDecisions(
            List<PhaseDecision> decisions,
            String aggregateId,
            ScopeGrade scope,
            Long expectedVersion) {

        Decision combined = Decision.ABSTAIN;
        String reasonCode = null;
        String deniedByPhase = null;

        for (PhaseDecision d : decisions) {
            if (d.decision() == Decision.DENY) {
                // Deny-overrides, earliest wins: stop at the first refusal so its reason is the one
                // that stands, and so no later PERMIT can flip a denied boundary open.
                combined = Decision.DENY;
                reasonCode = d.reasonCode();
                deniedByPhase = d.phaseName();
                break;
            }
            if (d.decision() == Decision.PERMIT) {
                combined = Decision.PERMIT;
            }
        }

        return new CommandPermitPlan(combined, reasonCode, deniedByPhase, aggregateId, scope, expectedVersion);
    }
}
