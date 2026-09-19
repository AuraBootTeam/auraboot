package com.auraboot.framework.bi.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.dto.AggregateQueryRequest;
import com.auraboot.framework.meta.dto.AggregateQueryResponse;
import com.auraboot.framework.meta.service.AggregateQueryService;
import com.auraboot.framework.meta.service.ChartRuntimeFilterResolver;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.service.UserPermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/** One query contract and access boundary for report aggregate previews and exports. */
@Service
@RequiredArgsConstructor
public class ReportAggregateQueryService {
    private final AggregateQueryService queries;
    private final ChartRuntimeFilterResolver runtimeFilters;
    private final UserPermissionService permissions;

    public void validateAccess(AggregateQueryRequest query) {
        if (query == null || !"aggregate".equals(query.getType())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "Report aggregate query is required");
        }
        int limit = query.getLimit() == null ? 100 : query.getLimit();
        if (limit < 1 || limit > 1000) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "Report aggregate limit must be between 1 and 1000");
        }
        String permission;
        if (query.getSemanticModelCode() != null && !query.getSemanticModelCode().isBlank()) {
            permission = MetaPermission.META_SEMANTIC_USE;
        } else {
            String model = query.getModelCode();
            if (model == null || !model.matches("[a-zA-Z][a-zA-Z0-9_]*")) {
                throw new ValidationException(ResponseCode.CommonValidationFailed, "Report aggregate modelCode is required");
            }
            permission = "model." + model + ".read";
        }
        Long userId = MetaContext.exists() ? MetaContext.getCurrentUserId() : null;
        if (userId == null || !permissions.hasPermission(userId, permission)) {
            throw new AccessDeniedException("Report aggregate data access denied");
        }
    }

    public AggregateQueryResponse execute(AggregateQueryRequest query) {
        validateAccess(query);
        if (query.getLimit() == null) query.setLimit(100);
        runtimeFilters.resolve(query);
        AggregateQueryResponse response = queries.execute(query);
        if (response == null || response.getRows() == null) {
            throw new IllegalStateException("Report aggregate query returned no result envelope");
        }
        return response;
    }
}
