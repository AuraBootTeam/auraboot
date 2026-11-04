package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPhase;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** User-supplied fields must be authorized before command permits or cached outcomes are used. */
@Component
@Order(280)
@RequiredArgsConstructor
public class CommandInputFieldAuthorizationPhase implements CommandPhase {
    private final DataPermissionEngine policies;

    @Override public String name() { return "input_field_authorization"; }

    @Override public void execute(CommandPipelineContext context) {
        if (context.getCommand() == null || context.getCommand().getModelCode() == null
                || context.getPayload() == null || context.getPayload().isEmpty()) return;
        // Machine and installed invocations use their separately verified writable-field contracts.
        if (com.auraboot.framework.application.security.MachineDataAccessContext.isActive()
                || com.auraboot.framework.openplatform.service.InstalledIntegrationExecution.active()
                || MetaContext.getCurrentMemberId() == null || MetaContext.getCurrentUserId() == null) return;
        var restricted = policies.getNonWritableFields(MetaContext.getCurrentTenantId(),
                context.getCommand().getModelCode(), MetaContext.getCurrentUserId());
        if (restricted != null && context.getPayload().keySet().stream().anyMatch(restricted::contains))
            throw new BusinessException(ResponseCode.FORBIDDEN, "Command input contains a non-writable field");
    }
}
