package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.entity.BindingRule;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * A command of type {@code query} that declares no handler of its own is read by the executor.
 *
 * <p>Such a command has no binding-rule handler and no plugin handler, primary or secondary, so
 * nothing else defines what it returns; it used to complete with an empty result. The handler
 * phase reads its model through the established READ authority and answers with the record the
 * request names or with a page. Every declarative write path is closed to it: its inputs select
 * records and are never an edit attempt, no field map runs for it, and its result is a response
 * that is never persisted onto a record. A query that declares a handler keeps that handler's
 * own contract.</p>
 */
public final class ExecutorReadQuery {

    private ExecutorReadQuery() {
    }

    public static boolean isQuery(Map<String, Object> execConfig) {
        return execConfig != null && "query".equalsIgnoreCase(String.valueOf(execConfig.get("type")));
    }

    public static boolean isReadByExecutor(CommandPipelineContext ctx, ExtensionRegistry registry) {
        if (ctx == null || ctx.getCommand() == null || !StringUtils.hasText(ctx.getCommand().getModelCode())
                || !isQuery(ctx.getExecConfig())) {
            return false;
        }
        if (ctx.getRulesByType() != null) {
            for (BindingRule rule : ctx.getRulesByType().getOrDefault("handler", List.of())) {
                if (rule != null && StringUtils.hasText(rule.getHandlerClass())) {
                    return false;
                }
            }
        }
        if (registry == null) {
            return true;
        }
        String handlerCode = CommandExecutorUtils.resolvePluginHandlerCode(
                ctx.getCommand().getCode(), ctx.getExecConfig());
        return registry.getCommandHandler(handlerCode).isEmpty()
                && registry.getSecondaryCommandHandlers(handlerCode).isEmpty();
    }
}
