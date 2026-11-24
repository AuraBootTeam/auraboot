package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.plugin.extension.BusinessResourceAuthorization;
import org.springframework.security.access.AccessDeniedException;
import java.util.*;

/** Owner-declared duties are checked before replay and again before privileged resource execution. */
public final class BusinessResourcePermissionContract {
    private BusinessResourcePermissionContract() {}
    public static List<String> require(CommandPipelineContext context,BusinessResourceAuthorization owner) {
        var declared=requireDeclaration(context,owner);
        if(context.getAuthorizationVerdict()==null || !context.getAuthorizationVerdict().isAuthorized()
                || !declared.contains(context.getAuthorizationVerdict().permissionCode()))
            throw new AccessDeniedException("Command duties differ from the business owner contract");
        return declared;
    }

    /** Immutable declaration only; this does not grant a duty or authorize an actor/resource. */
    public static List<String> requireDeclaration(CommandPipelineContext context,BusinessResourceAuthorization owner) {
        var boundary=owner.businessBoundary();
        Object params=context.getExecConfig()==null?null:context.getExecConfig().get("handlerParams");
        if(boundary==null || context.getCommand()==null || !boundary.commandCode().equals(context.getCommandCode())
                || !boundary.rootModel().equals(context.getCommand().getModelCode()) || !(params instanceof Map<?,?> values)
                || !"business".equals(values.get("targetRecordAuthorization")) || !boundary.targetAction().equals(values.get("targetRecordAction"))
                || !Boolean.FALSE.equals(values.get("dslPersistence"))
                || !("custom".equals(context.getExecConfig().get("type"))
                    || "update".equals(context.getExecConfig().get("type")) && "update".equals(boundary.targetAction()))
                || List.of("async","commandCreatedModels","relatedUpdateAuthorization","relatedDeleteAuthorization","invocationAuthorization")
                    .stream().anyMatch(values::containsKey)
                || context.getRulesByType()!=null && context.getRulesByType().values().stream().anyMatch(rules->rules!=null && !rules.isEmpty()))
            throw new AccessDeniedException("Command identity differs from the business owner contract");
        @SuppressWarnings("unchecked") var settings=(Map<String,Object>)params;
        BusinessTenantSecretContract.requireMetadata(settings,BusinessTenantSecretContract.references(owner));
        Set<String> expected=boundary.permissions();
        Object raw=context.getExecConfig()==null?null:context.getExecConfig().get("permissions");
        if(expected.isEmpty() || expected.size()>128 || expected.stream().anyMatch(code->code.isBlank()
                || !code.equals(code.strip()) || code.length()>255 || Set.of("*","meta.command.execute").contains(code))
                || !(raw instanceof List<?> declared) || declared.size()!=expected.size()
                || declared.stream().anyMatch(code->!(code instanceof String)) || !new HashSet<>(declared).equals(expected))
            throw new AccessDeniedException("Command duties differ from the business owner contract");
        return declared.stream().map(String.class::cast).toList();
    }
}
