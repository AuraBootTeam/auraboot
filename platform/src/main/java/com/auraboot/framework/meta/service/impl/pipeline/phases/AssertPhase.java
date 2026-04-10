package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.meta.service.impl.CommandExecutorDelegate;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPhase;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 * Assert phase: binding-rule assertions, preconditions, and validation rules.
 */
@Slf4j
@Component
@Order(700)
@RequiredArgsConstructor
public class AssertPhase implements CommandPhase {

    private final CommandExecutorDelegate delegate;

    @Override
    public String name() {
        return "assert";
    }

    @Override
    public void execute(CommandPipelineContext ctx) {
        var assertRules = ctx.getRulesByType().getOrDefault("assert", Collections.emptyList());
        delegate.executeAssertPhase(assertRules, ctx.getPayload());
        delegate.executePreconditionsPhase(ctx.getExecConfig(), ctx.getPayload(),
                ctx.getTenantId(), ctx.getCommand(), ctx.getRequest());
        delegate.executeValidationPhase(ctx.getExecConfig(), ctx.getPayload(),
                ctx.getTenantId(), ctx.getCommand(), ctx.getRequest());
    }
}
