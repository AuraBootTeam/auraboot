package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import com.auraboot.framework.meta.service.MetaModelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.application.tenant.MetaContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import com.auraboot.framework.exception.ConflictException;
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
public class CommandCascadeDeleteExecutor {

    private final JdbcTemplate jdbc;
    private final MetaModelService metaModelService;
    private final ObjectProvider<DynamicDataService> dataService;

    /**
     * Execute cascade delete phase based on executionConfig.cascadeDelete configuration.
     *
     * @param execConfig the parsed execution config
     * @param tenantId   current tenant ID
     * @param request    the command execute request (must have targetRecordId)
     */
    @SuppressWarnings("unchecked")
    public void executeCascadeDeletePhase(Map<String, Object> execConfig, Long tenantId,
                                   CommandExecuteRequest request) {
        if (execConfig == null || !execConfig.containsKey("cascadeDelete")) {
            return;
        }

        List<Map<String, Object>> cascadeConfigs = (List<Map<String, Object>>) execConfig.get("cascadeDelete");
        if (cascadeConfigs == null || request == null || !StringUtils.hasText(request.getTargetRecordId())) {
            return;
        }

        if (tenantId == null || !tenantId.equals(MetaContext.getCurrentTenantId())) {
            throw new AccessDeniedException("Cascade delete requires the bound tenant");
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
                var model = metaModelService.getModelDefinition(childModel)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown cascade model"));
                ModelMutationGuard.assertDeleteAllowed(model);
                String childTable = metaModelService.getTableName(childModel);
                CommandExecutorUtils.validateSqlIdentifier(childTable, "CASCADE_DELETE table");
                List<Map<String, Object>> subCascades = (List<Map<String, Object>>) config.get("cascadeDelete");
                var childPids = new java.util.LinkedHashSet<String>();
                for (String parentId : parentRecordIds) {
                    Object typedParentId;
                    try {
                        typedParentId = Long.parseLong(parentId);
                    } catch (NumberFormatException e) {
                        typedParentId = parentId;
                    }
                    String selectSql = "SELECT pid FROM " + childTable
                            + " WHERE tenant_id = ? AND " + parentField + " = ?"
                            + (model.isSoftDelete() ? " AND deleted_flag = false" : "")
                            + " ORDER BY pid FOR UPDATE";
                    var childRows = jdbc.queryForList(selectSql, tenantId, typedParentId);
                    if (childRows != null) {
                        for (var row : childRows) {
                            if (row.get("pid") == null) throw new IllegalStateException("Cascade row has no PID");
                            childPids.add(row.get("pid").toString());
                        }
                    }
                }
                if (subCascades != null && !subCascades.isEmpty() && !childPids.isEmpty()) {
                    executeCascadeDeleteRecursive(subCascades, List.copyOf(childPids), tenantId);
                }
                // Use the production model writer: permissions, soft-delete, optimistic
                // guards and source events all participate in the command owner transaction.
                for (String childPid : childPids) dataService.getObject().delete(childModel, childPid);
                int totalDeleted = childPids.size();
                log.info("CASCADE_DELETE: deleted {} records from {} where {} IN [{}]",
                        totalDeleted, childModel, parentField,
                        parentRecordIds.size() <= 3 ? String.join(",", parentRecordIds) : parentRecordIds.size() + " ids");
            } catch (AccessDeniedException | ConflictException e) {
                throw e;
            } catch (Exception e) {
                log.error("CASCADE_DELETE failed for {}.{}={}: {}",
                        childModel, parentField, parentRecordIds, e.getMessage());
                throw new BusinessException(ResponseCode.BadParam,
                        "Failed to cascade delete " + childModel + ": " + e.getMessage());
            }
        }
    }
}
