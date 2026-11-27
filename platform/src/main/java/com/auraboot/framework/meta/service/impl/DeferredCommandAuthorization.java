package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipeline;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.plugin.extension.BusinessResourceAuthorization;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/** Fresh human delivery authorization. Persisted task input is intent, never an executable grant. */
@Component
@RequiredArgsConstructor
public class DeferredCommandAuthorization {
    private final CommandExecutionTransaction transactions;
    private final CommandPipeline pipeline;
    private final com.auraboot.framework.permission.mapper.HumanActorMapper actors;

    public record Decision(String authority, String scope, Long expectedVersion, Map<String,Object> payload,
                           Map<String,Object> handlerParams) {
        public Decision {
            payload = BusinessResourceAuthorization.snapshot(payload);
            handlerParams = BusinessResourceAuthorization.snapshot(handlerParams);
        }
    }

    public Decision authorize(String commandCode, String handlerCode, String modelCode, String recordPid,
                              Map<String,Object> payload, String requestId, Long expectedVersion) {
        var claim = AsyncTaskExecutionContext.require();
        Long tenant = MetaContext.getCurrentTenantId(), user = MetaContext.getCurrentUserId();
        if (commandCode == null || commandCode.isBlank() || handlerCode == null || handlerCode.isBlank()
                || tenant == null || tenant <= 0 || user == null || user <= 0
                || !Objects.equals(claim.tenantId(), tenant) || !Objects.equals(claim.createdBy(), user)
                || MetaContext.getCurrentMemberId() == null || MetaContext.getCurrentMemberId() <= 0
                || MetaContext.getCurrentUserPid() == null || MetaContext.getCurrentUserPid().isBlank()
                || MetaContext.hasCommandAuthority() || MetaContext.hasCommandPermitScope()
                || MetaContext.isImpersonating()
                || com.auraboot.framework.application.security.MachineDataAccessContext.isActive()
                || com.auraboot.framework.openplatform.service.InstalledIntegrationExecution.active())
            throw new AccessDeniedException("Deferred command identity does not match its claimed job");
        var identities = actors.findActive(tenant, user, MetaContext.getCurrentUserPid());
        if (identities == null || identities.size() != 1
                || !(identities.getFirst().get("user_id") instanceof Number currentUser)
                || currentUser.longValue() != user
                || !(identities.getFirst().get("member_id") instanceof Number currentMember)
                || currentMember.longValue() != MetaContext.getCurrentMemberId()
                || !Objects.equals(identities.getFirst().get("user_pid"), MetaContext.getCurrentUserPid()))
            throw new AccessDeniedException("Deferred command requires a current matching human identity");
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new AccessDeniedException("Deferred authorization must finish before checkpoint execution");
        var request = new CommandExecuteRequest();
        request.setTargetRecordId(recordPid);
        request.setPayload(payload);
        request.setClientRequestId(requestId);
        if (expectedVersion != null) request.setExpectedVersion(Math.toIntExact(expectedVersion));
        var context = CommandPipelineContext.builder().commandCode(commandCode).request(request)
                .tenantId(tenant).userId(user).startTime(System.currentTimeMillis()).build();
        return transactions.execute(commandCode, false, () -> {
            pipeline.authorizeDeferredHandler(context, handlerCode, modelCode);
            var plan = context.getPermitPlan();
            Object raw = context.getExecConfig().get("handlerParams");
            @SuppressWarnings("unchecked") var params = raw instanceof Map<?,?> values
                    ? (Map<String,Object>) values : Map.<String,Object>of();
            return new Decision(context.getAuthorizationVerdict().permissionCode(),
                    plan.scope() == null ? null : plan.scope().name(), plan.expectedVersion(), context.getPayload(), params);
        });
    }
}
