package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.common.util.LogSanitizer;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.service.DataAccessAuthorizationContext;
import com.auraboot.framework.meta.service.DataAccessAuthorizationHelper;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import com.auraboot.framework.meta.service.base.BaseMetaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.function.Function;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataAccessAuthorizationHelperImpl extends BaseMetaService implements DataAccessAuthorizationHelper {

    private final DataPermissionEngine dataPermissionEngine;

    @Override
    public DataAccessAuthorizationContext authorizeList(String resourceCode, String actionCode) {
        validateResource(resourceCode);
        String resolvedAction = resolveAction(actionCode);
        Long tenantId = getCurrentTenantId();
        Long userId = getCurrentUserId();

        try {
            String rawFilter = dataPermissionEngine.buildRowFilter(tenantId, resourceCode, resolvedAction, userId);
            return new DataAccessAuthorizationContext(
                    tenantId, userId, resourceCode, resolvedAction, normalizeFilterClause(rawFilter));
        } catch (Exception e) {
            log.error("Failed to authorize list access for resource {} action {}",
                    LogSanitizer.safe(resourceCode), LogSanitizer.safe(resolvedAction), e);
            throw new MetaServiceException("Data permission evaluation failed for resource: " + resourceCode, e);
        }
    }

    @Override
    public boolean authorizeRecord(String resourceCode, String actionCode, Map<String, Object> record) {
        validateResource(resourceCode);
        String resolvedAction = resolveAction(actionCode);
        Long tenantId = getCurrentTenantId();
        Long userId = getCurrentUserId();

        try {
            if (!dataPermissionEngine.canAccessRecord(tenantId, resourceCode, resolvedAction, userId, record)) {
                throw new MetaServiceException("Access denied for resource: " + resourceCode);
            }
            return true;
        } catch (MetaServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to authorize record access for resource {} action {}",
                    LogSanitizer.safe(resourceCode), LogSanitizer.safe(resolvedAction), e);
            throw new MetaServiceException("Data permission evaluation failed for resource: " + resourceCode, e);
        }
    }

    @Override
    public boolean authorizeRecordId(String resourceCode, String actionCode, String recordId,
                                     Function<String, Map<String, Object>> recordLoader) {
        if (recordId == null || recordId.isBlank()) {
            throw new MetaServiceException("Record ID cannot be null or empty");
        }
        if (recordLoader == null) {
            throw new MetaServiceException("Record loader is required");
        }

        Map<String, Object> record;
        try {
            record = recordLoader.apply(recordId);
        } catch (Exception e) {
            throw new MetaServiceException("Failed to load record for authorization: " + recordId, e);
        }
        if (record == null || record.isEmpty()) {
            throw new MetaServiceException("Access denied for resource: " + resourceCode);
        }
        return authorizeRecord(resourceCode, actionCode, record);
    }

    private void validateResource(String resourceCode) {
        validateModelCode(resourceCode);
    }

    private String resolveAction(String actionCode) {
        if (actionCode == null || actionCode.isBlank()) {
            return "read";
        }
        String trimmed = actionCode.trim();
        if (!trimmed.matches("^[a-zA-Z][a-zA-Z0-9_:-]*$")) {
            throw new MetaServiceException("Invalid action code format: " + actionCode);
        }
        return trimmed;
    }

    private String normalizeFilterClause(String rawFilter) {
        if (rawFilter == null || rawFilter.isBlank()) {
            return "";
        }
        String normalized = rawFilter.trim();
        if (normalized.regionMatches(true, 0, "AND ", 0, 4)) {
            return normalized.substring(4).trim();
        }
        if (normalized.regionMatches(true, 0, "WHERE ", 0, 6)) {
            return normalized.substring(6).trim();
        }
        return normalized;
    }
}
