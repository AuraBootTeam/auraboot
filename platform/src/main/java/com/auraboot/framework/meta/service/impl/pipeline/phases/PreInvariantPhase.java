package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.meta.service.InvariantEngine;
import com.auraboot.framework.meta.service.impl.CommandExecutorDelegate;
import com.auraboot.framework.meta.service.impl.CommandStateCheckExecutor;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPhase;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@Order(800)
@RequiredArgsConstructor
public class PreInvariantPhase implements CommandPhase {

    private final InvariantEngine invariantEngine;
    private final CommandStateCheckExecutor stateCheckExecutor;
    private final CommandExecutorDelegate delegate;

    @Override
    public String name() {
        return "pre_invariant";
    }

    @Override
    public void execute(CommandPipelineContext ctx) {
        // Pre-invariant
        String stateField = stateCheckExecutor.getStateFieldForModel(ctx.getCommand().getModelCode());
        String currentState = (ctx.getRequest() != null
                && StringUtils.hasText(ctx.getRequest().getTargetRecordId()) && stateField != null)
                ? stateCheckExecutor.readCurrentState(ctx.getTenantId(), ctx.getCommand().getModelCode(),
                        ctx.getRequest().getTargetRecordId(), stateField)
                : null;
        invariantEngine.evaluatePreInvariants(
                ctx.getTenantId(), ctx.getCommand().getCode(), ctx.getCommand().getModelCode(),
                ctx.getPayload(), ctx.getRequest() != null ? ctx.getRequest().getTargetRecordId() : null,
                currentState);

        // Cross-field rules
        delegate.executeCrossFieldRules(ctx.getCommand(), ctx.getPayload(), ctx.getExecConfig());

        // Resolve plugin handler flags
        ctx.setHasPluginHandler(delegate.hasPluginHandler(ctx.getCommand().getCode()));
        ctx.setPluginRequiresDslPersistence(ctx.isHasPluginHandler()
                && delegate.shouldExecuteDslPersistenceWithPlugin(ctx.getExecConfig(), ctx.getRequest()));
    }
}
