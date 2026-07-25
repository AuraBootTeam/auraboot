package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPermitPlan;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPermitPlan.PhaseDecision;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The assembly point folds the authorization phases' recorded findings into the one
 * {@link CommandPermitPlan} the data layer will later execute (authz §11.15, buildable step 3).
 *
 * <p>The combination algebra itself (deny-overrides) is proven in {@code CommandPermitPlanTest};
 * these tests prove the <em>phase</em> feeds it the right inputs and stashes the result — in
 * particular that a boundary where nothing granted stays {@code ABSTAIN}, never a silent permit.</p>
 */
class PermitPlanAssemblyPhaseTest {

    private final PermitPlanAssemblyPhase phase = new PermitPlanAssemblyPhase();

    @Test
    @DisplayName("a granted boundary with only abstaining gates assembles to PERMIT")
    void assemblesPermitWhenGranterPermittedAndGatesAbstained() {
        CommandPipelineContext ctx = ctx("pur_01KPID");
        ctx.recordPhaseDecision(PhaseDecision.permit("authorization"));
        ctx.recordPhaseDecision(PhaseDecision.abstain("target_scope"));
        ctx.recordPhaseDecision(PhaseDecision.abstain("entitlement_check"));
        ctx.recordPhaseDecision(PhaseDecision.abstain("sod_check"));

        phase.execute(ctx);

        assertThat(ctx.getPermitPlan().decision()).isEqualTo(CommandPermitPlan.Decision.PERMIT);
    }

    @Test
    @DisplayName("any gate's DENY wins the whole plan, carrying its reason and phase")
    void assemblesDenyWhenAGateDenied() {
        CommandPipelineContext ctx = ctx("pur_01KPID");
        ctx.recordPhaseDecision(PhaseDecision.permit("authorization"));
        ctx.recordPhaseDecision(PhaseDecision.deny("plugin_entitlement_required", "entitlement_check"));

        phase.execute(ctx);

        CommandPermitPlan plan = ctx.getPermitPlan();
        assertThat(plan.decision()).isEqualTo(CommandPermitPlan.Decision.DENY);
        assertThat(plan.reasonCode()).isEqualTo("plugin_entitlement_required");
        assertThat(plan.deniedByPhase()).isEqualTo("entitlement_check");
    }

    /**
     * The ~200 undeclared-permission commands: RBAC abstains and the gates have nothing to object to,
     * so the boundary abstains. It must NOT become a permit — an abstaining boundary is "no decision",
     * which the data layer treats separately from a grant (this is the whole point of §11.15.1's
     * granter/gate split). A gate that permitted here would resurrect the 2026-07-22 write oracle.
     */
    @Test
    @DisplayName("a boundary where nothing granted stays ABSTAIN, never a silent permit")
    void assemblesAbstainWhenEveryPhaseAbstained() {
        CommandPipelineContext ctx = ctx("pur_01KPID");
        ctx.recordPhaseDecision(PhaseDecision.abstain("authorization"));
        ctx.recordPhaseDecision(PhaseDecision.abstain("target_scope"));
        ctx.recordPhaseDecision(PhaseDecision.abstain("entitlement_check"));
        ctx.recordPhaseDecision(PhaseDecision.abstain("sod_check"));

        phase.execute(ctx);

        assertThat(ctx.getPermitPlan().decision()).isEqualTo(CommandPermitPlan.Decision.ABSTAIN);
    }

    @Test
    @DisplayName("the plan pins the aggregate root the request named (D4)")
    void carriesTheAggregateRootTheRequestNamed() {
        CommandPipelineContext ctx = ctx("pur_01KPID");
        ctx.recordPhaseDecision(PhaseDecision.permit("authorization"));

        phase.execute(ctx);

        assertThat(ctx.getPermitPlan().aggregateId()).isEqualTo("pur_01KPID");
    }

    @Test
    @DisplayName("a command that names no target carries no aggregate (e.g. a create)")
    void carriesNoAggregateWhenTheRequestNamesNoTarget() {
        CommandPipelineContext ctx = ctx(null);
        ctx.recordPhaseDecision(PhaseDecision.permit("authorization"));

        phase.execute(ctx);

        assertThat(ctx.getPermitPlan().aggregateId()).isNull();
    }

    /** Scope (D3) and version (D5) join the plan in the next slice; this phase leaves them unresolved. */
    @Test
    @DisplayName("scope and version are left unresolved for the next slice")
    void leavesScopeAndVersionUnresolved() {
        CommandPipelineContext ctx = ctx("pur_01KPID");
        ctx.recordPhaseDecision(PhaseDecision.permit("authorization"));

        phase.execute(ctx);

        assertThat(ctx.getPermitPlan().scope()).isNull();
        assertThat(ctx.getPermitPlan().expectedVersion()).isNull();
    }

    @Test
    @DisplayName("the assembled plan is stashed on the context for the data layer to read")
    void stashesThePlanOnTheContext() {
        CommandPipelineContext ctx = ctx("pur_01KPID");
        ctx.recordPhaseDecision(PhaseDecision.permit("authorization"));

        phase.execute(ctx);

        assertThat(ctx.getPermitPlan()).isNotNull();
    }

    private CommandPipelineContext ctx(String targetRecordId) {
        CommandExecuteRequest request = new CommandExecuteRequest();
        if (targetRecordId != null) {
            request.setTargetRecordId(targetRecordId);
        }
        return CommandPipelineContext.builder()
                .commandCode("mkt:approve_purchase")
                .request(request)
                .tenantId(99L)
                .userId(10L)
                .startTime(System.currentTimeMillis())
                .build();
    }
}
