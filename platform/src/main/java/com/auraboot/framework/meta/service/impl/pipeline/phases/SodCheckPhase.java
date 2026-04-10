package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.service.impl.SodService;
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
        if (ctx.getRequest() != null && StringUtils.hasText(ctx.getRequest().getTargetRecordId())) {
            try {
                entityId = Long.parseLong(ctx.getRequest().getTargetRecordId());
            } catch (NumberFormatException e) {
                // Non-numeric record IDs (e.g. PIDs) — skip entity-level SoD
            }
        }
        String actorName = MetaContext.exists() ? MetaContext.getCurrentUsername() : null;
        sodService.checkSod(ctx.getCommandCode(), ctx.getUserId(), actorName, entityType, entityId);
    }
}
