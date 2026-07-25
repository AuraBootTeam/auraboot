package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.SodViolationException;
import com.auraboot.framework.meta.service.impl.SodService;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPermitPlan;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPhase;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Separation of Duties enforcement.
 */
@Slf4j
@Component
@Order(500)
public class SodCheckPhase implements CommandPhase {

    @Autowired(required = false)
    private SodService sodService;

    /** A hard separation-of-duties conflict blocks the command. */
    static final String REASON_SOD_VIOLATION = "sod_violation";

    @Override
    public String name() {
        return "sod_check";
    }

    @Override
    public boolean shouldSkip(CommandPipelineContext ctx) {
        return sodService == null;
    }

    @Override
    public void execute(CommandPipelineContext ctx) {
        String entityType = ctx.getCommand().getModelCode();
        Long entityId = null;
        String entityPid = null;
        if (ctx.getRequest() != null && StringUtils.hasText(ctx.getRequest().getTargetRecordId())) {
            String targetRecordId = ctx.getRequest().getTargetRecordId().trim();
            try {
                entityId = Long.parseLong(targetRecordId);
            } catch (NumberFormatException e) {
                entityPid = targetRecordId;
            }
        }
        String actorName = MetaContext.exists() ? MetaContext.getCurrentUsername() : null;
        try {
            sodService.checkSod(ctx.getCommandCode(), ctx.getUserId(), actorName, entityType, entityId, entityPid);
            // No hard conflict — a gate with no objection, not a grant.
            ctx.recordPhaseDecision(CommandPermitPlan.PhaseDecision.abstain(name()));
        } catch (SodViolationException e) {
            // Record the refusal for the permit plan before rethrowing. Only the SoD violation is
            // caught — an infra failure is not a SoD denial and must
            // propagate unlabelled rather than be recorded as one.
            ctx.recordPhaseDecision(CommandPermitPlan.PhaseDecision.deny(REASON_SOD_VIOLATION, name()));
            throw e;
        }
    }
}
