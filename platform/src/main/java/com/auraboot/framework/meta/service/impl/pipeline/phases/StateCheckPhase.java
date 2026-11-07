package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.meta.service.impl.CommandStateCheckExecutor;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPhase;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.meta.service.impl.pipeline.RecordSnapshotReader;
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
    private final RecordSnapshotReader snapshotReader;

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
        if (com.auraboot.framework.meta.security.NativeBusinessCommandContract.declaresTransition(ctx)) {
            validateBusinessTransition(ctx);
            return;
        }
        // State validation also persists transitions. Capture the original record
        // after authorization/version locking, before that first mutation occurs.
        if (ctx.getBeforeSnapshot() == null && ctx.getRequest() != null
                && StringUtils.hasText(ctx.getRequest().getTargetRecordId())
                && StringUtils.hasText(ctx.getCommand().getModelCode())) {
            ctx.setBeforeSnapshot(snapshotReader.readRecordSnapshot(
                    ctx.getTenantId(), ctx.getCommand().getModelCode(), ctx.getRequest().getTargetRecordId()));
        }
        String targetState = stateCheckExecutor.executeStateCheckPhase(
                ctx.getCommand(), ctx.getPayload(), ctx.getTenantId(),
                ctx.getRequest(), ctx.getExecConfig());
        ctx.setTargetState(targetState);
    }

    /** Validate the locked root; the implicit writer persists state and server fields once. */
    private void validateBusinessTransition(CommandPipelineContext ctx) {
        var contract = com.auraboot.framework.meta.security.NativeBusinessCommandContract.require(ctx, extensionRegistry);
        var target = ctx.getBusinessTargetAuthorization();
        var decision = ctx.getBusinessGrantDecision();
        String pid = ctx.getRequest().getTargetRecordId();
        if (target == null || decision == null || !decision.result().granted() || decision.grants().isEmpty()
                || target.tenantId() == null || target.tenantId() <= 0 || target.userId() == null || target.userId() <= 0
                || target.memberId() == null || target.memberId() <= 0
                || !org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                || org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || com.auraboot.framework.application.security.MachineDataAccessContext.isActive()
                || com.auraboot.framework.openplatform.service.InstalledIntegrationExecution.active()
                || !java.util.Objects.equals(ctx.getCommandCode(), com.auraboot.framework.application.tenant.MetaContext.getAuthorizedCommandCode())
                || !java.util.Objects.equals(target.commandCode(), ctx.getCommandCode())
                || !java.util.Objects.equals(target.modelCode(), contract.model()) || !"update".equals(target.action())
                || !java.util.Objects.equals(target.targetRecordId(), pid)
                || !java.util.Objects.equals(ctx.getTenantId(), target.tenantId())
                || !java.util.Objects.equals(ctx.getUserId(), target.userId())
                || !java.util.Objects.equals(target.tenantId(), com.auraboot.framework.application.tenant.MetaContext.getCurrentTenantId())
                || !java.util.Objects.equals(target.userId(), com.auraboot.framework.application.tenant.MetaContext.getCurrentUserId())
                || !java.util.Objects.equals(target.memberId(), com.auraboot.framework.application.tenant.MetaContext.getCurrentMemberId())
                || !java.util.Objects.equals(decision.tenantId(), target.tenantId())
                || !java.util.Objects.equals(decision.userId(), target.userId())
                || !java.util.Objects.equals(decision.memberId(), target.memberId())
                || !java.util.Objects.equals(decision.modelCode(), contract.model()) || !java.util.Objects.equals(decision.recordPid(), pid)
                || ctx.getTargetRecordVersion() == null || ctx.getTargetRecordVersion() < 0
                || !java.util.Objects.equals(ctx.getTargetRecordVersion(),
                    com.auraboot.framework.application.tenant.MetaContext.getCommandExpectedVersion(contract.model(), pid)))
            throw new org.springframework.security.access.AccessDeniedException("A locked business transition target is required");
        var record = snapshotReader.readRecordSnapshot(ctx.getTenantId(), contract.model(), pid);
        if (record == null || !pid.equals(record.get("pid")) || Boolean.TRUE.equals(record.get("deleted_flag"))
                || !java.util.Objects.equals(String.valueOf(target.tenantId()), String.valueOf(record.get("tenant_id")))
                || !(record.get("row_version") instanceof Number version) || version.longValue() != ctx.getTargetRecordVersion())
            throw new org.springframework.security.access.AccessDeniedException("Business transition state is unavailable");
        com.auraboot.framework.meta.security.NativeBusinessCommandContract.validateTransitionState(ctx.getExecConfig(), record, false);
        ctx.setBeforeSnapshot(record);
        ctx.setTargetState((String) ctx.getExecConfig().get("toState"));
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
