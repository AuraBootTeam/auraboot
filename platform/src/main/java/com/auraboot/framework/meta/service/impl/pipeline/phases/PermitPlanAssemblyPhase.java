package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPermitPlan;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPhase;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import lombok.extern.slf4j.Slf4j;
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
                null,   // row scope (D3) — resolved in the next slice
                null);  // expected version (D5) — captured in the next slice
        ctx.setPermitPlan(plan);

        if (log.isDebugEnabled()) {
            log.debug("Permit plan assembled for {}: decision={} deniedByPhase={} aggregateId={}",
                    ctx.getCommandCode(), plan.decision(), plan.deniedByPhase(), aggregateId);
        }
    }
}
