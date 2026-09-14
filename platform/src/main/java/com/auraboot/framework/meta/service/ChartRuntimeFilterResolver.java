package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.dto.AggregateQueryRequest;
import com.auraboot.framework.organization.service.OrganizationService;
import com.auraboot.framework.permission.service.RecordShareService;
import com.auraboot.framework.application.tenant.MetaContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Resolves permission-aware runtime filters for every chart query entry point. */
@Service
@RequiredArgsConstructor
public class ChartRuntimeFilterResolver {
    private static final String CURRENT_DEPARTMENT_OWNER_PIDS_RESOLVER = "$currentDepartmentOwnerPids";
    private static final String CURRENT_SHARED_RECORD_PIDS_RESOLVER = "$currentSharedRecordPids";
    private final OrganizationService organizationService;
    private final RecordShareService recordShareService;

    public void resolve(AggregateQueryRequest request) {
        resolveRuntimeFilterValues(request.getFilters(), request.getModelCode());
        resolveRuntimeFilterValues(request.getDrillFilters(), request.getModelCode());
    }

    private void resolveRuntimeFilterValues(
            java.util.List<AggregateQueryRequest.FilterConfig> filters, String modelCode) {
        if (filters == null || filters.isEmpty()) {
            return;
        }
        for (AggregateQueryRequest.FilterConfig filter : filters) {
            if (filter == null) {
                continue;
            }
            filter.setValue(resolveRuntimeFilterValue(filter.getValue(), modelCode));
            resolveRuntimeFilterValues(filter.getChildren(), modelCode);
        }
    }

    private Object resolveRuntimeFilterValue(Object value, String modelCode) {
        if (value instanceof java.util.Map<?, ?> map
                && map.containsKey(CURRENT_DEPARTMENT_OWNER_PIDS_RESOLVER)) {
            Object resolverSpec = map.get(CURRENT_DEPARTMENT_OWNER_PIDS_RESOLVER);
            boolean includeSubDepartments = true;
            if (resolverSpec instanceof java.util.Map<?, ?> spec
                    && spec.get("includeSubDepartments") instanceof Boolean includeSub) {
                includeSubDepartments = includeSub;
            }
            return organizationService.getCurrentDepartmentUserPids(includeSubDepartments);
        }
        if (value instanceof java.util.Map<?, ?> map
                && map.containsKey(CURRENT_SHARED_RECORD_PIDS_RESOLVER)) {
            String action = "read";
            Object resolverSpec = map.get(CURRENT_SHARED_RECORD_PIDS_RESOLVER);
            if (resolverSpec instanceof java.util.Map<?, ?> spec && spec.get("action") != null) {
                action = String.valueOf(spec.get("action")).trim().toLowerCase();
            }
            if (!java.util.Set.of("read", "update").contains(action)) {
                throw new IllegalArgumentException(
                        CURRENT_SHARED_RECORD_PIDS_RESOLVER + " supports read or update only");
            }
            return recordShareService.getSharedRecordPids(
                    MetaContext.getCurrentTenantId(), modelCode, MetaContext.getCurrentUserId(),
                    MetaContext.getCurrentUserPid(), action);
        }
        if (value instanceof java.util.List<?> list) {
            java.util.List<Object> resolved = new java.util.ArrayList<>(list.size());
            for (Object item : list) {
                Object resolvedItem = resolveRuntimeFilterValue(item, modelCode);
                if (resolvedItem instanceof java.util.List<?> nested) {
                    resolved.addAll(nested);
                } else {
                    resolved.add(resolvedItem);
                }
            }
            return resolved;
        }
        return value;
    }
}
