package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.security.NativeBusinessCommandContract;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.permission.service.BusinessCommandRecordAuthorization;
import com.auraboot.framework.permission.service.FieldPermissionService;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** Validates a real uncommitted root before observers, and current roots before cached replay. */
@Service
@RequiredArgsConstructor
public class NativeBusinessCreateBoundary {
    private final DynamicDataService data;
    private final MetaModelService metadata;
    private final FieldPermissionService fields;
    private final DataPermissionEngine policies;
    private final BusinessCommandRecordAuthorization authorization;
    private final CommandResultProjectionService projection;
    private final ExtensionRegistry extensions;
    private final PlatformTransactionManager transactions;

    public void authorizeOutcome(CommandPipelineContext context, Map<String, Object> outcome) {
        var contract = require(context);
        String count = contract.model() + "_inserted";
        if (outcome == null || !outcome.keySet().equals(Set.of("recordPid", count))
                || !(outcome.get("recordPid") instanceof String pid)
                || !(outcome.get(count) instanceof Number number)) throw denied();
        try {
            if (new java.math.BigDecimal(number.toString()).intValueExact() != 1) throw denied();
        } catch (NumberFormatException | ArithmeticException malformed) { throw denied(); }
        authorizeRecord(context, pid);
        context.getBusinessResultProjection().validateEnvelope(outcome);
    }

    public void authorizeRecord(CommandPipelineContext context, String pid) {
        var contract = require(context);
        if (pid == null || !pid.matches("[0-9A-HJKMNP-TV-Z]{26}")
                || context.getRequest().getTargetRecordId() != null
                && !pid.equals(context.getRequest().getTargetRecordId())) throw denied();
        // Only this fixed model/PID read sees the uncommitted insert and its database defaults.
        // The caller never receives a generic model read or a broad execution permit.
        var row = MetaContext.runWithCommandPermitScope("ALL", () -> data.getById(contract.model(), pid));
        if (row == null || !pid.equals(row.get("pid"))) throw denied();
        var record = Collections.unmodifiableMap(new LinkedHashMap<>(row));
        TransactionTemplate snapshot = new TransactionTemplate(transactions);
        snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setReadOnly(true);
        // The outer insert retains its write transaction. Only grant reads use this consistent
        // snapshot; they do not commit the insert or need visibility of its uncommitted row.
        // Domain commands already established the coherent transaction and function decision.
        // Candidate/inserted-row data checks use that same snapshot, including their own write.
        boolean coherent = java.util.Set.of(TransactionDefinition.ISOLATION_REPEATABLE_READ, TransactionDefinition.ISOLATION_SERIALIZABLE)
                .contains(Objects.requireNonNullElse(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(), -1));
        var grant = coherent ? authorization.resolveNative(MetaContext.getCurrentMemberId(),
                contract.permissions(), contract.model(), record, "create")
                : snapshot.execute(status -> authorization.resolveNative(MetaContext.getCurrentMemberId(),
                    contract.permissions(), contract.model(), record, "create"));
        if (grant == null || !grant.result().granted()
                || !Objects.equals(grant.tenantId(), context.getTenantId())
                || !Objects.equals(grant.userId(), context.getUserId())
                || !Objects.equals(grant.memberId(), MetaContext.getCurrentMemberId())
                || !contract.model().equals(grant.modelCode()) || !pid.equals(grant.recordPid())) throw denied();
        Set<String> submitted = context.submittedInputKeys() == null
                ? context.getPayload().keySet() : context.submittedInputKeys();
        var inputs = (List<?>) context.getExecConfig().get("inputFields");
        if (!inputs.containsAll(submitted)) throw denied();
        if (!new FieldWriteAuthorizationSupport(metadata, fields, policies)
                .nonWritableInputKeys(contract.model(), submitted).isEmpty()) throw denied();
        var definitions = metadata.getModelFields(contract.model());
        if (definitions == null) throw denied();
        for (String input : submitted) {
            String canonical = definitions.stream().filter(field -> input.equals(field.getCode())
                            || input.equals(field.getColumnName())).map(field -> field.getCode())
                    .findFirst().orElseThrow(NativeBusinessCreateBoundary::denied);
            if (!grant.canWrite(canonical)) throw denied();
        }
        context.setTargetRecordReadable(true);
        context.setBusinessGrantDecision(grant);
        context.setBusinessTargetAuthorization(new CommandPipelineContext.BusinessTargetAuthorization(
                context.getCommandCode(), contract.model(), pid, "create", context.getTenantId(),
                context.getUserId(), MetaContext.getCurrentMemberId()));
        context.getRequest().setTargetRecordId(pid);
        context.setBusinessResultProjection(projection.prepareNative(context, contract));
    }

    private NativeBusinessCommandContract require(CommandPipelineContext context) {
        if (!NativeBusinessCommandContract.declaresCreation(context)
                || context.getNativeCreationContract() == null || !MetaContext.exists()
                || MetaContext.getCurrentMemberId() == null || MetaContext.getCurrentUserId() == null
                || MetaContext.getCurrentUserPid() == null || MetaContext.isImpersonating()
                || MetaContext.hasCommandPermitScope()
                || !Objects.equals(context.getTenantId(), MetaContext.getCurrentTenantId())
                || !Objects.equals(context.getUserId(), MetaContext.getCurrentUserId())
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || com.auraboot.framework.application.security.MachineDataAccessContext.isActive()
                || com.auraboot.framework.openplatform.service.InstalledIntegrationExecution.active()) throw denied();
        var contract = NativeBusinessCommandContract.require(context, extensions);
        if (!"create".equals(contract.action()) || !contract.equals(context.getNativeCreationContract())) throw denied();
        return contract;
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Native creation is outside its complete business grant");
    }
}
