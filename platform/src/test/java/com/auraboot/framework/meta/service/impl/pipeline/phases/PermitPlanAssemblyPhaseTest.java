package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPermitPlan;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPermitPlan.PhaseDecision;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPermitPlan.ScopeGrade;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The assembly point folds the authorization phases' recorded findings into the one
 * {@link CommandPermitPlan} the data layer will later execute (authz §11.15, buildable step 3), and
 * resolves the row-scope grade (D3) the plan runs under.
 *
 * <p>The combination algebra itself (deny-overrides) is proven in {@code CommandPermitPlanTest};
 * these tests prove the <em>phase</em> feeds it the right inputs, resolves scope faithfully from the
 * existing engine, and stashes the result — in particular that a boundary where nothing granted stays
 * {@code ABSTAIN}, never a silent permit.</p>
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
        ctx.setTargetRecordVersion(17L);
        ctx.recordPhaseDecision(PhaseDecision.permit("authorization"));

        phase.execute(ctx);

        assertThat(ctx.getPermitPlan().aggregateId()).isEqualTo("pur_01KPID");
        assertThat(ctx.getPermitPlan().expectedVersion())
                .as("the plan must carry the server-captured target version")
                .isEqualTo(17L);
    }

    @Test
    @DisplayName("a command that names no target carries no aggregate (e.g. a create)")
    void carriesNoAggregateWhenTheRequestNamesNoTarget() {
        CommandPipelineContext ctx = ctx(null);
        ctx.recordPhaseDecision(PhaseDecision.permit("authorization"));

        phase.execute(ctx);

        assertThat(ctx.getPermitPlan().aggregateId()).isNull();
    }

    // ---------- row scope (D3) ----------

    /** A caller the engine applies no row filter to has ALL access — the grade is ALL. */
    @Test
    @DisplayName("an empty row filter from the engine resolves to ALL scope")
    void resolvesAllScopeWhenTheEngineAppliesNoRowFilter() {
        DataPermissionEngine engine = Mockito.mock(DataPermissionEngine.class);
        when(engine.buildRowFilter(anyLong(), anyString(), anyLong())).thenReturn("");
        ReflectionTestUtils.setField(phase, "dataPermissionEngine", engine);
        CommandPipelineContext ctx = ctxWithModel("pur_01KPID", "mkt_purchase");
        ctx.recordPhaseDecision(PhaseDecision.permit("authorization"));

        phase.execute(ctx);

        assertThat(ctx.getPermitPlan().scope()).isEqualTo(ScopeGrade.ALL);
    }

    /** A non-blank row filter means the caller is restricted — the grade is SELF (D3, fail-closed). */
    @Test
    @DisplayName("a restricting row filter from the engine resolves to SELF scope")
    void resolvesSelfScopeWhenTheEngineRestrictsRows() {
        DataPermissionEngine engine = Mockito.mock(DataPermissionEngine.class);
        when(engine.buildRowFilter(anyLong(), anyString(), anyLong()))
                .thenReturn("AND (created_by = 10)");
        ReflectionTestUtils.setField(phase, "dataPermissionEngine", engine);
        CommandPipelineContext ctx = ctxWithModel("pur_01KPID", "mkt_purchase");
        ctx.recordPhaseDecision(PhaseDecision.permit("authorization"));

        phase.execute(ctx);

        assertThat(ctx.getPermitPlan().scope()).isEqualTo(ScopeGrade.SELF);
    }

    @Test
    @DisplayName("no engine leaves scope unresolved (minimal context)")
    void leavesScopeUnresolvedWhenNoEngineAvailable() {
        CommandPipelineContext ctx = ctxWithModel("pur_01KPID", "mkt_purchase");
        ctx.recordPhaseDecision(PhaseDecision.permit("authorization"));

        phase.execute(ctx);

        assertThat(ctx.getPermitPlan().scope()).isNull();
    }

    @Test
    @DisplayName("a command with no model leaves scope unresolved")
    void leavesScopeUnresolvedWhenTheCommandHasNoModel() {
        DataPermissionEngine engine = Mockito.mock(DataPermissionEngine.class);
        ReflectionTestUtils.setField(phase, "dataPermissionEngine", engine);
        CommandPipelineContext ctx = ctx("pur_01KPID"); // no command/model set
        ctx.recordPhaseDecision(PhaseDecision.permit("authorization"));

        phase.execute(ctx);

        assertThat(ctx.getPermitPlan().scope()).isNull();
    }

    /**
     * An unresolved grade is never published as an authoritative plan. The phase may therefore
     * leave it unresolved without widening access; the legacy data-layer path remains in force.
     */
    @Test
    @DisplayName("a scope-resolution failure leaves scope unresolved and does not break the command")
    void leavesScopeUnresolvedWhenResolutionErrors() {
        DataPermissionEngine engine = Mockito.mock(DataPermissionEngine.class);
        when(engine.buildRowFilter(anyLong(), anyString(), anyLong()))
                .thenThrow(new RuntimeException("engine down"));
        ReflectionTestUtils.setField(phase, "dataPermissionEngine", engine);
        CommandPipelineContext ctx = ctxWithModel("pur_01KPID", "mkt_purchase");
        ctx.recordPhaseDecision(PhaseDecision.permit("authorization"));

        assertThatCode(() -> phase.execute(ctx)).doesNotThrowAnyException();
        assertThat(ctx.getPermitPlan()).isNotNull();
        assertThat(ctx.getPermitPlan().scope()).isNull();
    }

    /** A command with no captured target version remains unversioned. */
    @Test
    @DisplayName("version remains unresolved when the target boundary captured none")
    void leavesVersionUnresolved() {
        CommandPipelineContext ctx = ctx("pur_01KPID");
        ctx.recordPhaseDecision(PhaseDecision.permit("authorization"));

        phase.execute(ctx);

        assertThat(ctx.getPermitPlan().expectedVersion()).isNull();
    }

    // ---------- §11.10 stage-1 shadow observe ----------

    /**
     * An undeclared command (ABSTAIN) is the migration surface — it must be metered so the surface is
     * visible before enforcement flips on. Legacy allowed it (it reached the boundary); the plan does
     * not authorize it.
     */
    @Test
    @DisplayName("an undeclared (ABSTAIN) command is metered under its decision")
    void metersTheAbstainDivergenceSurface() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReflectionTestUtils.setField(phase, "meterRegistry", registry);
        CommandPipelineContext ctx = ctx("pur_01KPID");
        ctx.recordPhaseDecision(PhaseDecision.abstain("authorization"));

        phase.execute(ctx);

        assertThat(registry.counter(PermitPlanAssemblyPhase.SHADOW_DECISION_METRIC, "decision", "ABSTAIN")
                .count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a granted command is metered as PERMIT")
    void metersPermitDecision() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReflectionTestUtils.setField(phase, "meterRegistry", registry);
        CommandPipelineContext ctx = ctx("pur_01KPID");
        ctx.recordPhaseDecision(PhaseDecision.permit("authorization"));

        phase.execute(ctx);

        assertThat(registry.counter(PermitPlanAssemblyPhase.SHADOW_DECISION_METRIC, "decision", "PERMIT")
                .count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("assembly works without a meter registry (minimal context)")
    void assemblesWithoutAMeterRegistry() {
        CommandPipelineContext ctx = ctx("pur_01KPID");
        ctx.recordPhaseDecision(PhaseDecision.abstain("authorization"));

        assertThatCode(() -> phase.execute(ctx)).doesNotThrowAnyException();
        assertThat(ctx.getPermitPlan()).isNotNull();
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
        return ctxWithModel(targetRecordId, null);
    }

    private CommandPipelineContext ctxWithModel(String targetRecordId, String modelCode) {
        CommandExecuteRequest request = new CommandExecuteRequest();
        if (targetRecordId != null) {
            request.setTargetRecordId(targetRecordId);
        }
        CommandPipelineContext ctx = CommandPipelineContext.builder()
                .commandCode("mkt:approve_purchase")
                .request(request)
                .tenantId(99L)
                .userId(10L)
                .startTime(System.currentTimeMillis())
                .build();
        if (modelCode != null) {
            CommandDefinition command = new CommandDefinition();
            command.setModelCode(modelCode);
            ctx.setCommand(command);
        }
        return ctx;
    }
}
