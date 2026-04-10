package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.meta.service.impl.CommandExecutorDelegate;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPhase;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Order(1100)
@RequiredArgsConstructor
public class ComputedFieldsPhase implements CommandPhase {

    private final CommandExecutorDelegate delegate;

    @Override public String name() { return "computed_fields"; }

    @Override
    public void execute(CommandPipelineContext ctx) {
        delegate.executeComputedFieldsPhase(ctx.getExecConfig(), ctx.getPayload(),
                ctx.getTenantId(), ctx.getCommand(), ctx.getRequest(), ctx.getFieldMapResults());
        delegate.recordChangeTracking(ctx.getCommand(), ctx.getRequest(),
                ctx.getTenantId(), ctx.getUserId(), ctx.getBeforeSnapshot());
    }
}
