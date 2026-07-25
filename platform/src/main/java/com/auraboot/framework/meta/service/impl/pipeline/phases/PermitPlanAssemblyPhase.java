package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPermitPlan;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPermitPlan.ScopeGrade;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPhase;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Fold the authorization phases' findings into one {@link CommandPermitPlan} (authz architecture
 * §11.15, buildable step 3 — the assembly point).
 *
 * <p>This runs once, after the last authorization phase ({@code SodCheckPhase} at order 500) and
 * before any data mutation ({@code HandlerPhase} at 1200). By now every authorization phase — the
 * one granter (RBAC at 200) and the gates (BOLA 250, entitlement 400, SoD 500) — has recorded its
 * {@link CommandPermitPlan.PhaseDecision} on the context. This phase combines them by
 * <strong>deny-overrides</strong> ({@link CommandPermitPlan#fromPhaseDecisions}) into the boundary's
 * single decision, and pins the aggregate root (D4) the request named so derived writes can inherit
 * it. The two invariant phases (state 600, pre-invariant 800) are axis B and deliberately do not feed
 * this plan (§11.15.1).</p>
 *
 * <p><strong>Phase-1 Shadow.</strong> The plan is assembled and stashed on the context, but nothing
 * enforces it yet — the data layer still runs its own {@code isDataPermissionBypassed} decision. The
 * next slice makes the data layer read this plan's scope predicate and version instead of deciding
 * again. Assembling it here changes no behaviour; it only makes the decision available. Row scope
 * (D3) and the optimistic version (D5) are not resolved yet — they join the plan in the following
 * slice — so both are carried as {@code null} for now.</p>
 */
@Slf4j
@Component
@Order(550)
public class PermitPlanAssemblyPhase implements CommandPhase {

    /**
     * The existing row-scope engine — the same one the data layer calls at each of its ~40 sites
     * today. We call it once here, at the boundary, so the plan carries the scope grade instead of
     * each site re-deciding it (the whole point of §11.15). Optional so a minimal context without the
     * engine still assembles a plan (scope simply stays unresolved).
     */
    @Autowired(required = false)
    private DataPermissionEngine dataPermissionEngine;

    @Override
    public String name() {
        return "permit_plan_assembly";
    }

    @Override
    public void execute(CommandPipelineContext ctx) {
        CommandExecuteRequest request = ctx.getRequest();
        // The aggregate root is the master document the request named — the same id HandlerPhase pins
        // the handler stage to. Derived writes inherit authorization from it (D4). Null when the
        // command names no target (e.g. a create).
        String aggregateId = request != null && StringUtils.hasText(request.getTargetRecordId())
                ? request.getTargetRecordId()
                : null;

        CommandPermitPlan plan = CommandPermitPlan.fromPhaseDecisions(
                ctx.getPhaseDecisions(),
                aggregateId,
                resolveScope(ctx),
                null);  // expected version (D5) — captured at enforcement (step 4)
        ctx.setPermitPlan(plan);

        if (log.isDebugEnabled()) {
            log.debug("Permit plan assembled for {}: decision={} deniedByPhase={} aggregateId={} scope={}",
                    ctx.getCommandCode(), plan.decision(), plan.deniedByPhase(), aggregateId, plan.scope());
        }
    }

    /**
     * Resolve the row-scope grade (D3) the whole command runs under, from the existing row-scope
     * engine's own verdict — so the plan's grade can never drift from what the engine actually
     * filters. {@code buildRowFilter} returns a blank fragment exactly when the caller has ALL access
     * (or no row policy at all — the unrestricted default), and a non-blank fragment when it is
     * restricted (SELF, and — folded to SELF for phase-1, per §11.13 — DEPARTMENT / CUSTOM). Multiple
     * roles are already combined most-permissive-wins inside the engine, so a caller with any ALL
     * grant comes back blank → {@link ScopeGrade#ALL}.
     *
     * <p>Returns {@code null} (unresolved) when the inputs to decide a grade are absent, or when the
     * engine is unavailable or errors. This is deliberate <strong>shadow safety</strong>: nothing
     * enforces the grade yet, so resolving it here must not add a failure the command would not
     * otherwise hit — the data layer's own fail-secure evaluation is still the one in force. Step 4
     * makes this boundary resolution authoritative and fail-secure, retiring the per-site calls.</p>
     */
    private ScopeGrade resolveScope(CommandPipelineContext ctx) {
        if (dataPermissionEngine == null) {
            return null;
        }
        CommandDefinition command = ctx.getCommand();
        String modelCode = command == null ? null : command.getModelCode();
        Long tenantId = ctx.getTenantId();
        Long userId = ctx.getUserId();
        if (modelCode == null || tenantId == null || userId == null) {
            return null;
        }
        try {
            String rowFilter = dataPermissionEngine.buildRowFilter(tenantId, modelCode, userId);
            return StringUtils.hasText(rowFilter) ? ScopeGrade.SELF : ScopeGrade.ALL;
        } catch (RuntimeException e) {
            // Shadow safety only: the grade is not consumed yet, so a resolution failure here must
            // not break a command the data layer would have run. The data layer keeps its own
            // fail-secure evaluation until step 4 moves enforcement onto this plan.
            log.warn("Shadow scope resolution failed for {} (model {}); leaving scope unresolved",
                    ctx.getCommandCode(), modelCode, e);
            return null;
        }
    }
}
