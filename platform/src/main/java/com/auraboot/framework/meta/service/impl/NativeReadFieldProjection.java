package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.permission.service.BusinessCommandRecordAuthorization;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** Projects ordinary human reads from complete grants evaluated against canonical host-loaded rows. */
@Service
@RequiredArgsConstructor
public class NativeReadFieldProjection {
    private final BusinessCommandRecordAuthorization authorization;
    private final PlatformTransactionManager transactions;

    static void prepareQuery(ModelDefinition model, com.auraboot.framework.meta.service.QueryBuilderService.QueryBuilder query) {
        if (MetaContext.exists() && MetaContext.getCurrentMemberId() != null
                && !com.auraboot.framework.application.security.MachineDataAccessContext.isActive()
                && !com.auraboot.framework.openplatform.service.InstalledIntegrationExecution.active()
                && !MetaContext.hasCommandPermitScopeFor(model.getCode())) query.includeNativeReadAuthorization();
    }

    /** Keep physical rows and grants in one MVCC view, including the writer's own changes. */
    public <T> T readWithinSnapshot(String modelCode, java.util.function.Supplier<T> operation) {
        if (!MetaContext.exists() || MetaContext.getCurrentMemberId() == null
                || com.auraboot.framework.application.security.MachineDataAccessContext.isActive()
                || com.auraboot.framework.openplatform.service.InstalledIntegrationExecution.active()
                || MetaContext.hasCommandPermitScopeFor(modelCode)) return operation.get();
        if (coherentSnapshot()) return operation.get();
        // A weak write snapshot cannot be repaired after its own mutations or row reads.
        // Keep the owning transaction intact and require its caller to establish isolation.
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                && !org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw denied();
        return snapshot().execute(status -> operation.get());
    }

    private static boolean coherentSnapshot() {
        Integer isolation = org.springframework.transaction.support.TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        return org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                && isolation != null && (isolation == TransactionDefinition.ISOLATION_REPEATABLE_READ
                || isolation == TransactionDefinition.ISOLATION_SERIALIZABLE);
    }

    private TransactionTemplate snapshot() {
        var snapshot = new TransactionTemplate(transactions);
        snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setReadOnly(true);
        return snapshot;
    }

    public List<Map<String,Object>> project(ModelDefinition model, List<Map<String,Object>> records) {
        if (records.isEmpty()) return records;
        if (com.auraboot.framework.application.security.MachineDataAccessContext.isActive()
                || com.auraboot.framework.openplatform.service.InstalledIntegrationExecution.active()
                || MetaContext.hasCommandPermitScopeFor(model.getCode())) throw denied();
        // Background execution without a human member retains its separate authorization path.
        if (!MetaContext.exists() || MetaContext.getCurrentMemberId() == null) return records;
        if (coherentSnapshot()) return projectInSnapshot(model, records);
        // Starting a new transaction here could combine an already-loaded row with newer grants.
        // Physical callers must enter readWithinSnapshot before selecting the record.
        throw denied();
    }

    /** Internal change snapshots never become ordinary public write results without a read check. */
    public Map<String,Object> publicWriteResult(ModelDefinition model, Map<String,Object> actualRecord,
                                                java.util.function.Supplier<Map<String,Object>> publicReader) {
        if (com.auraboot.framework.application.security.MachineDataAccessContext.isActive()
                || com.auraboot.framework.openplatform.service.InstalledIntegrationExecution.active()
                || MetaContext.hasCommandPermitScopeFor(model.getCode())
                || BusinessResourceDataScope.retainsWriteResult(model.getCode(), Objects.toString(actualRecord.get("pid"),null)))
            return actualRecord;
        if (!MetaContext.exists() || MetaContext.getCurrentMemberId() == null) return actualRecord;
        if (!coherentSnapshot() || !(actualRecord.get("pid") instanceof String pid) || pid.isBlank()
                || !Objects.equals(actualRecord.get("tenant_id"), MetaContext.getCurrentTenantId())) throw denied();
        Map<String,Object> projected;
        try {
            projected = publicReader.get();
        } catch (AccessDeniedException readDenied) {
            // This is the defined write-only acknowledgement, not a replacement read authority.
            // Configuration/storage failures still propagate; only read denial yields an envelope.
            var envelope = new LinkedHashMap<String,Object>();
            envelope.put("pid",pid);
            if (actualRecord.containsKey("row_version")) envelope.put("row_version",actualRecord.get("row_version"));
            return envelope;
        }
        if (projected == null || !pid.equals(projected.get("pid"))) throw denied();
        return projected;
    }

    private List<Map<String,Object>> projectInSnapshot(ModelDefinition model, List<Map<String,Object>> records) {
            List<Map<String,Object>> result = new ArrayList<>(records.size());
            for (var record : records) {
                if (!(record.get("__ab_auth_pid") instanceof String pid) || pid.isBlank()
                        || record.get("__ab_auth_tenant") == null || !record.containsKey("__ab_auth_creator")) throw denied();
                Map<String,Object> canonical = new LinkedHashMap<>(record);
                canonical.keySet().removeIf(key -> key.startsWith("__ab_auth_"));
                for (var field : model.getFields()) {
                    if (record.containsKey(field.getColumnName())) canonical.put(field.getCode(), record.get(field.getColumnName()));
                }
                canonical.put("pid", pid);
                canonical.put("tenant_id", record.get("__ab_auth_tenant"));
                canonical.put("created_by", record.get("__ab_auth_creator"));
                var grant = authorization.resolveOrdinaryRead(MetaContext.getCurrentMemberId(), model.getCode(), canonical);
                if (grant == null || !grant.result().granted()
                        || !Objects.equals(grant.tenantId(), MetaContext.getCurrentTenantId())
                        || !Objects.equals(grant.userId(), MetaContext.getCurrentUserId())
                        || !Objects.equals(grant.memberId(), MetaContext.getCurrentMemberId())
                        || !model.getCode().equals(grant.modelCode())
                        || !Objects.equals(grant.recordPid(), pid)) throw denied();
                Map<String,Object> projected = new LinkedHashMap<>();
                for (var entry : record.entrySet()) {
                    if (entry.getKey().startsWith("__ab_auth_")) continue;
                    String fieldCode = model.getFields().stream()
                            .filter(field -> entry.getKey().equals(field.getColumnName()))
                            .map(field -> field.getCode()).findFirst().orElse(entry.getKey());
                    // Record identity and optimistic concurrency tokens are envelope fields, not business data.
                    boolean declared = model.getFields().stream().anyMatch(field -> fieldCode.equals(field.getCode()))
                            || com.auraboot.framework.meta.constant.SystemFieldConstants.isSystemField(fieldCode);
                    boolean legacy = grant.grants().stream().anyMatch(binding -> binding.fields().unrestricted());
                    if (Set.of("pid", "row_version").contains(entry.getKey()) || (declared || legacy) && grant.canRead(fieldCode))
                        projected.put(entry.getKey(), entry.getValue());
                }
                projected.put("pid", pid);
                result.add(projected);
            }
            return result;
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Native record fields require a coherent transaction and complete current read grant");
    }
}
