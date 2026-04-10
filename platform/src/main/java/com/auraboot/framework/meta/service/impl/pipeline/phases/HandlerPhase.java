package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.meta.service.impl.CommandExecutorDelegate;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPhase;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;

@Slf4j
@Component
@Order(1200)
@RequiredArgsConstructor
public class HandlerPhase implements CommandPhase {

    private final CommandExecutorDelegate delegate;

    @Override public String name() { return "handler"; }

    @Override
    public void execute(CommandPipelineContext ctx) {
        var handlerRules = ctx.getRulesByType().getOrDefault("handler", Collections.emptyList());
        Map<String, Object> handlerResults = delegate.executeHandlerPhase(
                handlerRules, ctx.getCommand(), ctx.getPayload(), ctx.getFieldMapResults(),
                ctx.getTenantId(), ctx.getUserId(), ctx.getRequest(), ctx.getExecConfig());
        ctx.setHandlerResults(handlerResults);
        delegate.persistHandlerResults(ctx.getCommand().getModelCode(), ctx.getPayload(),
                handlerResults, ctx.getTenantId(), ctx.getRequest(), ctx.getFieldMapResults());
    }
}
