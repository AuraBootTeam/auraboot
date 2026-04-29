package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.meta.service.impl.CommandStateCheckExecutor;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPhase;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * State machine transition check (delegates to already-extracted CommandStateCheckExecutor).
 */
@Slf4j
@Component
@Order(600)
@RequiredArgsConstructor
public class StateCheckPhase implements CommandPhase {

    private final CommandStateCheckExecutor stateCheckExecutor;
    private final ExtensionRegistry extensionRegistry;

    @Override
    public String name() {
        return "state_check";
    }

    @Override
    public boolean shouldSkip(CommandPipelineContext ctx) {
        return isPluginHandledWithoutDslPersistence(ctx);
    }

    @Override
    public void execute(CommandPipelineContext ctx) {
        String targetState = stateCheckExecutor.executeStateCheckPhase(
                ctx.getCommand(), ctx.getPayload(), ctx.getTenantId(),
                ctx.getRequest(), ctx.getExecConfig());
        ctx.setTargetState(targetState);
    }

    private boolean isPluginHandledWithoutDslPersistence(CommandPipelineContext ctx) {
        if (ctx.isHasPluginHandler()) {
            return !ctx.isPluginRequiresDslPersistence();
        }
        if (extensionRegistry == null || ctx.getCommand() == null) {
            return false;
        }
        String handlerCode = resolvePluginHandlerCode(ctx.getCommand().getCode(), ctx.getExecConfig());
        return extensionRegistry.getCommandHandler(handlerCode)
                .map(handler -> {
                    boolean requiresPersistence = handler.requiresDslPersistence(
                            handlerCode, ctx.getExecConfig(), ctx.getRequest());
                    ctx.setHasPluginHandler(true);
                    ctx.setPluginRequiresDslPersistence(requiresPersistence);
                    return !requiresPersistence;
                })
                .orElse(false);
    }

    private String resolvePluginHandlerCode(String commandCode, Map<String, Object> execConfig) {
        if (execConfig != null) {
            Object handler = execConfig.get("handler");
            if (handler instanceof String handlerCode && StringUtils.hasText(handlerCode)) {
                return handlerCode.trim();
            }
        }
        return commandCode;
    }
}
