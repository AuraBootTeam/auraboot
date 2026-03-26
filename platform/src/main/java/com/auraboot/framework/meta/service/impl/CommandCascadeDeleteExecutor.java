package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles cascade delete operations for the command execution pipeline.
 * Supports nested multi-level cascade deletes (e.g., plan -> items -> monthly_amounts).
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
class CommandCascadeDeleteExecutor {

    private final DynamicDataMapper dynamicDataMapper;
    private final MetaModelService metaModelService;

    /**
     * Execute cascade delete phase based on executionConfig.cascadeDelete configuration.
     *
     * @param execConfig the parsed execution config
     * @param tenantId   current tenant ID
     * @param request    the command execute request (must have targetRecordId)
     */
    @SuppressWarnings("unchecked")
    void executeCascadeDeletePhase(Map<String, Object> execConfig, Long tenantId,
                                   CommandExecuteRequest request) {
        if (execConfig == null || !execConfig.containsKey("cascadeDelete")) {
            return;
        }

        List<Map<String, Object>> cascadeConfigs = (List<Map<String, Object>>) execConfig.get("cascadeDelete");
        if (cascadeConfigs == null || request == null || !StringUtils.hasText(request.getTargetRecordId())) {
            return;
        }

        executeCascadeDeleteRecursive(cascadeConfigs, List.of(request.getTargetRecordId()), tenantId);
    }

    /**
     * Recursively execute cascade deletes. Supports nested cascadeDelete configs
     * for multi-level relationships (e.g., plan -> items -> monthly_amounts).
     * For each cascade level, first collects child PIDs, recurses into sub-cascades,
     * then deletes the children.
     */
    @SuppressWarnings("unchecked")
    private void executeCascadeDeleteRecursive(List<Map<String, Object>> cascadeConfigs,
                                               List<String> parentRecordIds, Long tenantId) {
        for (Map<String, Object> config : cascadeConfigs) {
            String childModel = (String) config.get("childModel");
            String parentField = (String) config.get("parentField");
            if (childModel == null || parentField == null) continue;

            // Security: validate parentField to prevent SQL injection
            CommandExecutorUtils.validateSqlIdentifier(parentField, "CASCADE_DELETE parentField");

            try {
                String childTable = metaModelService.getTableName(childModel);

                // Collect child PIDs before deleting (needed for nested cascades)
                List<Map<String, Object>> subCascades = (List<Map<String, Object>>) config.get("cascadeDelete");
                if (subCascades != null && !subCascades.isEmpty()) {
                    for (String parentId : parentRecordIds) {
                        String selectSql = "SELECT pid FROM " + childTable
                                + " WHERE " + parentField + " = #{params.parentId}";
                        // Convert parentId to Long for integer column compatibility
                        Object typedParentId;
                        try {
                            typedParentId = Long.parseLong(parentId);
                        } catch (NumberFormatException e) {
                            typedParentId = parentId;
                        }
                        List<Map<String, Object>> childRows = dynamicDataMapper.selectByQuery(
                                selectSql, Map.of("parentId", typedParentId));
                        if (childRows != null && !childRows.isEmpty()) {
                            List<String> childPids = childRows.stream()
                                    .map(r -> String.valueOf(r.get("pid")))
                                    .toList();
                            executeCascadeDeleteRecursive(subCascades, childPids, tenantId);
                        }
                    }
                }

                // Delete children for all parent IDs
                int totalDeleted = 0;
                for (String parentId : parentRecordIds) {
                    Map<String, Object> conditions = new HashMap<>();
                    conditions.put("tenant_id", tenantId);
                    // Convert parentId to Long for integer column compatibility in PostgreSQL
                    // (prevents "operator does not exist: integer = character varying")
                    try {
                        conditions.put(parentField, Long.parseLong(parentId));
                    } catch (NumberFormatException e) {
                        conditions.put(parentField, parentId);
                    }
                    totalDeleted += dynamicDataMapper.delete(childTable, conditions);
                }
                log.info("CASCADE_DELETE: deleted {} records from {} where {} IN [{}]",
                        totalDeleted, childModel, parentField,
                        parentRecordIds.size() <= 3 ? String.join(",", parentRecordIds) : parentRecordIds.size() + " ids");
            } catch (Exception e) {
                log.error("CASCADE_DELETE failed for {}.{}={}: {}",
                        childModel, parentField, parentRecordIds, e.getMessage());
                throw new BusinessException(ResponseCode.BadParam,
                        "Failed to cascade delete " + childModel + ": " + e.getMessage());
            }
        }
    }
}
