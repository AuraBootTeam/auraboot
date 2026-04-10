package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.meta.service.impl.CommandAutoSetExecutor;
import com.auraboot.framework.meta.service.impl.CommandExecutorDelegate;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPhase;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Order(900)
@RequiredArgsConstructor
public class AutoSetPhase implements CommandPhase {

    private final CommandAutoSetExecutor autoSetExecutor;
    private final CommandExecutorDelegate delegate;

    @Override
    public String name() {
        return "auto_set";
    }

    @Override
    public boolean shouldSkip(CommandPipelineContext ctx) {
        return ctx.isHasPluginHandler() && !ctx.isPluginRequiresDslPersistence();
    }

    @Override
    public void execute(CommandPipelineContext ctx) {
        autoSetExecutor.executeAutoSetPhase(ctx.getExecConfig(), ctx.getPayload(),
                ctx.getTenantId(), ctx.getUserId(), ctx.getCommand());
        delegate.executeCommandFieldValidationPhase(ctx.getExecConfig(), ctx.getPayload(),
                ctx.getCommand(), ctx.getRequest());
    }
}
