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
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.auraboot.framework.plugin.pf4j.CommandRecordActionScopeAccessorFactory recordScopeFactory;

    @Override public String name() { return "invocation_authorization"; }

    @Override public boolean shouldSkip(CommandPipelineContext context) {
        return !(context.getExecConfig().get("handlerParams") instanceof Map<?, ?> params)
                || !params.containsKey("invocationAuthorization");
    }

    @Override public void execute(CommandPipelineContext context) {
        context.setHandlerInvocationAuthorization(null);
        var params = (Map<?, ?>) context.getExecConfig().get("handlerParams");
        if (!("handler".equals(params.get("invocationAuthorization")) || "handler-with-result-reads".equals(params.get("invocationAuthorization"))) || !MetaContext.exists()
                || context.getTenantId() == null || !Objects.equals(context.getTenantId(), MetaContext.getCurrentTenantId())
                || MetaContext.getCurrentMemberId() == null || !StringUtils.hasText(MetaContext.getCurrentUserPid())) {
            throw denied();
        }
        if ("handler-with-result-reads".equals(params.get("invocationAuthorization"))) {
            IdempotencyPhase.cachedReads(context);
            if (recordScopeFactory == null) throw denied();
        }
        Object configured = context.getExecConfig().get("handler");
        String code = configured instanceof String value && StringUtils.hasText(value) ? value.trim() : context.getCommandCode();
        var handler = extensions.getCommandHandler(code).orElseThrow(CommandInvocationAuthorizationPhase::denied);
        if (!(handler instanceof CommandInvocationAuthorizer authorizer)) throw denied();
        var request = context.getRequest();
        var scope = recordScopeFactory == null ? null : recordScopeFactory.forInvocation(context);
        try {
            var invocation = new CommandInvocationAuthorizer.InvocationContext(context.getTenantId(),
                    MetaContext.getCurrentMemberId(), MetaContext.getCurrentUserPid(), context.getCommandCode(), code,
                    context.getCommand() == null ? null : context.getCommand().getModelCode(),
                    request == null ? null : request.getTargetRecordId(), request == null ? null : request.getPayload(),
                    scope == null ? null : scope.accessor());
            if (!authorizer.authorizeInvocation(invocation)) throw denied();
        } finally { if (scope != null) scope.close(); }
        context.setHandlerInvocationAuthorization(new CommandPipelineContext.HandlerInvocationAuthorization(
                context.getCommandCode(), context.getCommand() == null ? null : context.getCommand().getModelCode(),
                request == null ? null : request.getTargetRecordId(),
                params.get("targetRecordAction") instanceof String action ? action : null));
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Command invocation is not authorized");
    }
}
