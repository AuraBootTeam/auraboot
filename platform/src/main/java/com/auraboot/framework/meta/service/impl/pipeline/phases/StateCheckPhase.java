package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.meta.service.impl.CommandStateCheckExecutor;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPhase;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * State machine transition check (delegates to already-extracted CommandStateCheckExecutor).
 */
@Slf4j
@Component
@Order(600)
@RequiredArgsConstructor
public class StateCheckPhase implements CommandPhase {

    private final CommandStateCheckExecutor stateCheckExecutor;

    @Override
    public String name() {
        return "state_check";
    }

    @Override
    public void execute(CommandPipelineContext ctx) {
        String targetState = stateCheckExecutor.executeStateCheckPhase(
                ctx.getCommand(), ctx.getPayload(), ctx.getTenantId(),
                ctx.getRequest(), ctx.getExecConfig());
        ctx.setTargetState(targetState);
    }
}
