package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.plugin.extension.BackgroundDataAccessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Default {@link BackgroundDataAccessor} that delegates to
 * {@link DynamicDataService} after binding the caller-supplied tenant id to
 * the current thread via {@link MetaContext}. Restores the prior tenant on
 * exit so nested calls (an outer command that invoked into background code)
 * see no state leak.
 *
 * @since 2.5.0
 */
@Slf4j
@Service
public class BackgroundDataAccessorImpl implements BackgroundDataAccessor {

    private final DynamicDataService dynamicDataService;

    public BackgroundDataAccessorImpl(DynamicDataService dynamicDataService) {
        this.dynamicDataService = dynamicDataService;
    }

    @Override
    public Map<String, Object> create(long tenantId, String modelCode, Map<String, Object> data) {
        return withTenant(tenantId, () -> dynamicDataService.create(modelCode, data));
    }

    @Override
    public Optional<Map<String, Object>> tryCreate(long tenantId, String modelCode, Map<String, Object> data) {
        return withTenant(tenantId, () -> {
            try {
                return Optional.of(dynamicDataService.create(modelCode, data));
            } catch (DuplicateKeyException e) {
                log.debug("tryCreate hit unique violation (idempotent skip): tenant={} model={} cause={}",
                        tenantId, modelCode, e.getMostSpecificCause().getMessage());
                return Optional.<Map<String, Object>>empty();
            }
        });
    }

    @Override
    public Map<String, Object> getById(long tenantId, String modelCode, String recordId) {
        return withTenant(tenantId, () -> dynamicDataService.getById(modelCode, recordId));
    }

    @Override
    public List<Map<String, Object>> query(long tenantId, String modelCode, Map<String, Object> filters) {
        return withTenant(tenantId, () -> {
            List<QueryCondition> conditions = new ArrayList<>();
            if (filters != null) {
                for (Map.Entry<String, Object> entry : filters.entrySet()) {
                    conditions.add(QueryCondition.builder()
                            .fieldName(entry.getKey())
                            .operator(QueryCondition.Operator.EQ)
                            .value(entry.getValue())
                            .build());
                }
            }
            DynamicQueryRequest request = DynamicQueryRequest.builder()
                    .pageNum(1)
                    .pageSize(10000)
                    .conditions(conditions)
                    .build();
            PaginationResult<Map<String, Object>> result = dynamicDataService.list(modelCode, request);
            return result.getRecords() != null ? result.getRecords() : List.<Map<String, Object>>of();
        });
    }

    @Override
    public Map<String, Object> update(long tenantId, String modelCode, String recordId, Map<String, Object> data) {
        return withTenant(tenantId, () -> dynamicDataService.update(modelCode, recordId, data));
    }

    @Override
    public void delete(long tenantId, String modelCode, String recordId) {
        withTenant(tenantId, () -> {
            dynamicDataService.delete(modelCode, recordId);
            return null;
        });
    }

    @Override
    public Optional<Long> incrementWithinCap(long tenantId, String modelCode, String recordId,
                                              String counterCode, long delta, String capCode) {
        return withTenant(tenantId, () -> dynamicDataService.incrementWithinCap(modelCode, recordId, counterCode, delta, capCode));
    }

    /** Synthetic user id for background-context writes. ab_data_change_log
     * requires non-null changed_by; this matches DigestService convention. */
    private static final long SYSTEM_USER_ID = 0L;

    /**
     * Bind {@code tenantId} to the current thread, run the supplier, restore
     * whatever tenant (if any) was on the thread before. Always restores,
     * even if the supplier throws.
     */

    private <T> T withTenant(long tenantId, Supplier<T> work) {
        boolean hadPriorContext = MetaContext.exists();
        Long priorTenant = hadPriorContext ? MetaContext.getCurrentTenantId() : null;
        Long priorUser = hadPriorContext ? MetaContext.getCurrentUserId() : null;
        String priorUserPid = hadPriorContext ? MetaContext.getCurrentUserPid() : null;
        String priorUsername = hadPriorContext ? MetaContext.getCurrentUsername() : null;
        Long priorMember = MetaContext.getCurrentMemberId();
        Long priorEnv = MetaContext.getCurrentEnvironmentId();
        java.util.Set<Long> priorRoles = hadPriorContext
                ? MetaContext.getCurrentRoleIds() : java.util.Set.of();
        // Bind full context (tenant + synthetic system user) so DynamicDataServiceImpl's
        // populateSystemFields finds a non-null userId for changed_by / created_by.
        MetaContext.setContext(tenantId, SYSTEM_USER_ID, null, "system");
        MetaContext.setMemberId(null);
        MetaContext.setEnvironmentId(priorEnv);
        try {
            return MetaContext.runWithoutDataPermission(work);
        } finally {
            if (hadPriorContext) {
                MetaContext.setContext(priorTenant, priorUser, priorUserPid, priorUsername, priorRoles);
                MetaContext.setMemberId(priorMember);
                MetaContext.setEnvironmentId(priorEnv);
            } else {
                MetaContext.clear();
            }
        }
    }
}
