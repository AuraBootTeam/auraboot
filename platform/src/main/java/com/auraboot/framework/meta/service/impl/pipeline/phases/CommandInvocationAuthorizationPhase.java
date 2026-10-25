package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPhase;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.plugin.extension.CommandInvocationAuthorizer;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Explicit handler-owned domain authorization, enforced before any cached command outcome is returned. */
@Component
@Order(240)
@RequiredArgsConstructor
public class CommandInvocationAuthorizationPhase implements CommandPhase {
    private final ExtensionRegistry extensions;

    @Override public String name() { return "invocation_authorization"; }

    @Override public boolean shouldSkip(CommandPipelineContext context) {
        return !(context.getExecConfig().get("handlerParams") instanceof Map<?, ?> params)
                || !params.containsKey("invocationAuthorization");
    }

    @Override public void execute(CommandPipelineContext context) {
        var params = (Map<?, ?>) context.getExecConfig().get("handlerParams");
        if (!"handler".equals(params.get("invocationAuthorization")) || !MetaContext.exists()
                || context.getTenantId() == null || !Objects.equals(context.getTenantId(), MetaContext.getCurrentTenantId())
                || MetaContext.getCurrentMemberId() == null || !StringUtils.hasText(MetaContext.getCurrentUserPid())) {
            throw denied();
        }
        Object configured = context.getExecConfig().get("handler");
        String code = configured instanceof String value && StringUtils.hasText(value) ? value.trim() : context.getCommandCode();
        var handler = extensions.getCommandHandler(code).orElseThrow(CommandInvocationAuthorizationPhase::denied);
        if (!(handler instanceof CommandInvocationAuthorizer authorizer)) throw denied();
        var request = context.getRequest();
        var invocation = new CommandInvocationAuthorizer.InvocationContext(context.getTenantId(),
                MetaContext.getCurrentMemberId(), MetaContext.getCurrentUserPid(), context.getCommandCode(), code,
                context.getCommand() == null ? null : context.getCommand().getModelCode(),
                request == null ? null : request.getTargetRecordId(), context.getPayload());
        if (!authorizer.authorizeInvocation(invocation)) throw denied();
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Command invocation is not authorized");
    }
}
