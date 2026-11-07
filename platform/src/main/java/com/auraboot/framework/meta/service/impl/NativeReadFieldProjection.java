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

    public List<Map<String,Object>> project(ModelDefinition model, List<Map<String,Object>> records) {
        if (records.isEmpty()) return records;
        if (com.auraboot.framework.application.security.MachineDataAccessContext.isActive()
                || com.auraboot.framework.openplatform.service.InstalledIntegrationExecution.active()
                || MetaContext.hasCommandPermitScopeFor(model.getCode())) throw denied();
        // Background execution without a human member retains its separate authorization path.
        if (!MetaContext.exists() || MetaContext.getCurrentMemberId() == null) return records;
        var snapshot = new TransactionTemplate(transactions);
        snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setReadOnly(true);
        // Like native-create authorization, grant reads use one fresh snapshot without committing
        // an enclosing business write. The supplied canonical row never leaves this host boundary.
        return Objects.requireNonNull(snapshot.execute(status -> {
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
                    // PID is the record envelope identity. All data and audit fields remain scoped.
                    boolean declared = model.getFields().stream().anyMatch(field -> fieldCode.equals(field.getCode()))
                            || com.auraboot.framework.meta.constant.SystemFieldConstants.isSystemField(fieldCode);
                    boolean legacy = grant.grants().stream().anyMatch(binding -> binding.fields().unrestricted());
                    if ("pid".equals(entry.getKey()) || (declared || legacy) && grant.canRead(fieldCode))
                        projected.put(entry.getKey(), entry.getValue());
                }
                projected.put("pid", pid);
                result.add(projected);
            }
            return result;
        }));
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Native record fields require a complete current read grant");
    }
}
