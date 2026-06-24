package com.auraboot.framework.permission.service;

import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.security.SqlSafetyUtils;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.permission.entity.PermissionAuditLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * Resolves pid values for permission audit rows that still store internal numeric identity.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PermissionAuditRecordPidResolver {

    private final MetaModelService metaModelService;
    private final DynamicDataMapper dynamicDataMapper;

    public String resolve(PermissionAuditLog logEntry) {
        Long internalNumericId = logEntry.getRecordId();
        if (internalNumericId == null || !StringUtils.hasText(logEntry.getResourceCode())) {
            return null;
        }
        try {
            MetaModelDTO model = metaModelService.findByCode(logEntry.getResourceCode());
            if (model == null || !StringUtils.hasText(model.getTableName())) {
                return null;
            }
            String tableName = SqlSafetyUtils.requireIdentifier(model.getTableName(), "permission audit model table");
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(
                    "SELECT pid FROM " + tableName + " WHERE tenant_id = #{params.tenantId} AND id = #{params.internalNumericId}",
                    Map.of("tenantId", logEntry.getTenantId(), "internalNumericId", internalNumericId));
            if (rows.isEmpty()) {
                return null;
            }
            Object pid = rows.get(0).get("pid");
            return pid != null ? pid.toString() : null;
        } catch (Exception ex) {
            log.debug("Permission audit pid resolution skipped: resourceCode={}, internalNumericId={}, reason={}",
                    logEntry.getResourceCode(), internalNumericId, ex.getMessage());
            return null;
        }
    }
}
