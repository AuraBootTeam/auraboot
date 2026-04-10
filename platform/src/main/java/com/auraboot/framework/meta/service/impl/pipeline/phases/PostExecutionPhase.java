package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.meta.service.impl.CommandEffectExecutor;
import com.auraboot.framework.meta.service.impl.CommandExecutorDelegate;
import com.auraboot.framework.meta.service.impl.CommandSideEffectExecutor;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPhase;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 * Groups consistency check, side effects, roll-up, governance snapshot, and post-action.
 */
@Slf4j
@Component
@Order(1300)
@RequiredArgsConstructor
public class PostExecutionPhase implements CommandPhase {

    private final CommandSideEffectExecutor sideEffectExecutor;
    private final CommandExecutorDelegate delegate;

    @Override public String name() { return "post_execution"; }

    @Override
    public void execute(CommandPipelineContext ctx) {
        // Consistency check
        delegate.executeConsistencyCheckPhase(ctx.getCommand(), ctx.getPayload(),
                ctx.getFieldMapResults(), ctx.getTenantId(), ctx.getExecConfig());

        // Side effects
        sideEffectExecutor.executeSideEffectPhase(ctx.getExecConfig(), ctx.getPayload(),
                ctx.getTenantId(), ctx.getUserId(), ctx.getCommand(), ctx.getRequest(), ctx.getFieldMapResults());

        // Roll-up recalculation
        delegate.executeRollUpRecalculation(ctx.getCommand().getModelCode(), ctx.getPayload(),
                ctx.getFieldMapResults(), ctx.getTenantId());

        // Governance snapshot
        delegate.executeGovernanceSnapshot(ctx.getCommand().getModelCode(), ctx.getPayload(),
                ctx.getFieldMapResults(), ctx.getTenantId(), ctx.getUserId());

        // Post actions
        delegate.executePostActionPhase(ctx.getExecConfig(), ctx.getPayload(),
                ctx.getTenantId(), ctx.getUserId(), ctx.getCommand(), ctx.getRequest(), ctx.getFieldMapResults());
    }
}
