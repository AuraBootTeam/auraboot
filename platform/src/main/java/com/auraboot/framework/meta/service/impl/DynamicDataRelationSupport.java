package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.common.util.LogSanitizer;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import com.auraboot.framework.meta.service.DataDomainService;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.util.JsonbFieldHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.util.*;
import java.util.stream.Collectors;

/** Executes relation queries and mutations inside the caller's service transaction. */
@Slf4j
@RequiredArgsConstructor
final class DynamicDataRelationSupport {
    private final DynamicDataMapper dynamicDataMapper;
    private final DataPermissionEngine dataPermissionEngine;
    private final DataDomainService dataDomainService;
    private final java.util.function.Function<String, ModelDefinition> modelResolver;
    private static String logSafe(Object value) { return LogSanitizer.safe(value); }
    // ==================== Relation Data ====================

    List<Map<String, Object>> getRelationData(RelationDefinition relation, String recordId, Map<String, Object> queryParams, Long tenantId, Long userId) {


        // Security: validate all relation SQL identifiers to prevent injection
        java.util.regex.Pattern NAME_PATTERN = java.util.regex.Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");
        if (relation.getTargetTable() != null && !NAME_PATTERN.matcher(relation.getTargetTable()).matches()) {
            throw new com.auraboot.framework.exception.BusinessException("Invalid relation target table: " + relation.getTargetTable());
        }
        if (relation.getTargetField() != null && !NAME_PATTERN.matcher(relation.getTargetField()).matches()) {
            throw new com.auraboot.framework.exception.BusinessException("Invalid relation target field: " + relation.getTargetField());
        }
        if (relation.getSourceField() != null && !NAME_PATTERN.matcher(relation.getSourceField()).matches()) {
            throw new com.auraboot.framework.exception.BusinessException("Invalid relation source field: " + relation.getSourceField());
        }
        if (relation.getJoinTable() != null && !NAME_PATTERN.matcher(relation.getJoinTable()).matches()) {
            throw new com.auraboot.framework.exception.BusinessException("Invalid relation join table: " + relation.getJoinTable());
        }

        if (relation.getRelationType() == RelationDefinition.RelationType.MANY_TO_MANY) {
            // Many-to-many: query join table first, then target table
            String joinSql = "SELECT " + relation.getTargetField() + " FROM " + relation.getJoinTable()
                    + " WHERE " + relation.getSourceField() + " = #{params.recordId}"
                    + " AND tenant_id = #{params.tenantId}";
            Map<String, Object> joinParams = new HashMap<>();
            joinParams.put("recordId", recordId);
            joinParams.put("tenantId", tenantId);

            List<Map<String, Object>> joinResults = dynamicDataMapper.selectByQuery(joinSql, joinParams);
            if (joinResults.isEmpty()) {
                return Collections.emptyList();
            }

            // Extract target IDs
            List<Object> targetIds = joinResults.stream()
                    .map(row -> row.get(relation.getTargetField()))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            if (targetIds.isEmpty()) {
                return Collections.emptyList();
            }

            // Query target table — use parameterized IN clause to prevent SQL injection
            Map<String, Object> targetParams = new HashMap<>();
            targetParams.put("tenantId", tenantId);

            StringBuilder inPlaceholders = new StringBuilder();
            for (int i = 0; i < targetIds.size(); i++) {
                if (i > 0) inPlaceholders.append(",");
                String paramKey = "id_" + i;
                inPlaceholders.append("#{params.").append(paramKey).append("}");
                targetParams.put(paramKey, targetIds.get(i));
            }

            StringBuilder targetSqlBuilder = new StringBuilder();
            targetSqlBuilder.append("SELECT * FROM ").append(relation.getTargetTable())
                    .append(" WHERE id IN (").append(inPlaceholders).append(")")
                    .append(" AND tenant_id = #{params.tenantId}");

            // Row-level permission filter on target model (fail-secure)
            String targetModelCode = relation.getTargetModel();
            try {
                String rowFilter = dataPermissionEngine.buildRowFilter(tenantId, targetModelCode, userId);
                if (rowFilter != null && !rowFilter.isBlank()) {
                    targetSqlBuilder.append(" ").append(rowFilter);
                }
            } catch (Exception e) {
                log.error("Failed to apply row-level permission in getRelationData for target: {} — denying access", logSafe(targetModelCode), e);
                throw new MetaServiceException("Data permission evaluation failed for relation query", e);
            }

            // Domain isolation filter on target model (fail-secure)
            try {
                String domainFilter = dataDomainService.buildDomainFilter(targetModelCode, userId);
                if (domainFilter != null && !domainFilter.isBlank()) {
                    targetSqlBuilder.append(" ").append(domainFilter);
                }
            } catch (Exception e) {
                log.error("Failed to apply domain filter in getRelationData for target: {} — denying access", logSafe(targetModelCode), e);
                throw new MetaServiceException("Data domain filter failed for relation query", e);
            }

            List<Map<String, Object>> targetResults = dynamicDataMapper.selectByQuery(targetSqlBuilder.toString(), targetParams);

            // Column masking on target model results (fail-secure)
            try {
                List<FieldMaskRule> maskRules = dataPermissionEngine.getFieldMaskRules(tenantId, targetModelCode, userId);
                if (maskRules != null && !maskRules.isEmpty()) {
                    targetResults = dataPermissionEngine.applyFieldMasking(targetResults, maskRules);
                }
            } catch (Exception e) {
                log.error("Failed to apply field masking in getRelationData for target: {} — denying access", logSafe(targetModelCode), e);
                throw new MetaServiceException("Field masking failed for relation query", e);
            }

            // Read-shape contract: json/jsonb fields leave as JSON strings, never PGobject.
            JsonbFieldHelper.normalizeJsonReadValues(modelResolver.apply(targetModelCode), targetResults);

            return targetResults;
        } else {
            // One-to-many / Many-to-one: direct query on target table
            StringBuilder sqlBuilder = new StringBuilder();
            sqlBuilder.append("SELECT * FROM ").append(relation.getTargetTable())
                    .append(" WHERE ").append(relation.getTargetField()).append(" = #{params.recordId}")
                    .append(" AND tenant_id = #{params.tenantId}");
            Map<String, Object> params = new HashMap<>();
            params.put("recordId", recordId);
            params.put("tenantId", tenantId);

            // Row-level permission filter on target model (fail-secure)
            String targetModelCode = relation.getTargetModel();
            try {
                String rowFilter = dataPermissionEngine.buildRowFilter(tenantId, targetModelCode, userId);
                if (rowFilter != null && !rowFilter.isBlank()) {
                    sqlBuilder.append(" ").append(rowFilter);
                }
            } catch (Exception e) {
                log.error("Failed to apply row-level permission in getRelationData for target: {} — denying access", logSafe(targetModelCode), e);
                throw new MetaServiceException("Data permission evaluation failed for relation query", e);
            }

            // Domain isolation filter on target model (fail-secure)
            try {
                String domainFilter = dataDomainService.buildDomainFilter(targetModelCode, userId);
                if (domainFilter != null && !domainFilter.isBlank()) {
                    sqlBuilder.append(" ").append(domainFilter);
                }
            } catch (Exception e) {
                log.error("Failed to apply domain filter in getRelationData for target: {} — denying access", logSafe(targetModelCode), e);
                throw new MetaServiceException("Data domain filter failed for relation query", e);
            }

            // Apply limit from queryParams
            if (queryParams != null && queryParams.containsKey("limit")) {
                sqlBuilder.append(" LIMIT ").append(Integer.parseInt(queryParams.get("limit").toString()));
            }

            List<Map<String, Object>> relationResults = dynamicDataMapper.selectByQuery(sqlBuilder.toString(), params);

            // Column masking on target model results (fail-secure)
            try {
                List<FieldMaskRule> maskRules = dataPermissionEngine.getFieldMaskRules(tenantId, targetModelCode, userId);
                if (maskRules != null && !maskRules.isEmpty()) {
                    relationResults = dataPermissionEngine.applyFieldMasking(relationResults, maskRules);
                }
            } catch (Exception e) {
                log.error("Failed to apply field masking in getRelationData for target: {} — denying access", logSafe(targetModelCode), e);
                throw new MetaServiceException("Field masking failed for relation query", e);
            }

            // Read-shape contract: json/jsonb fields leave as JSON strings, never PGobject.
            JsonbFieldHelper.normalizeJsonReadValues(modelResolver.apply(targetModelCode), relationResults);

            return relationResults;
        }
    }

    // ==================== Relation CRUD ====================

    RelationOperationResult createRelations(RelationDefinition relation, String recordId, List<String> targetRecordIds, Long tenantId) {
        if (relation.getRelationType() != RelationDefinition.RelationType.MANY_TO_MANY) {
            throw new MetaServiceException("createRelations only supports MANY_TO_MANY relations. Use update for other types.");
        }

        List<String> successIds = new ArrayList<>();
        List<String> failedIds = new ArrayList<>();

        for (String targetId : targetRecordIds) {
            try {
                Map<String, Object> data = new HashMap<>();
                data.put(relation.getSourceField(), recordId);
                data.put(relation.getTargetField(), targetId);
                data.put("tenant_id", tenantId);
                data.put("created_at", java.time.Instant.now());

                dynamicDataMapper.insert(relation.getJoinTable(), data);
                successIds.add(targetId);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // Relation already exists — treat as success (idempotent)
                log.debug("Relation already exists for target {}, treating as success", logSafe(targetId));
                successIds.add(targetId);
            } catch (Exception e) {
                log.warn("Failed to create relation for target {}: {}", logSafe(targetId), logSafe(e.getMessage()), e);
                failedIds.add(targetId);
            }
        }

        boolean allSuccess = failedIds.isEmpty();
        return RelationOperationResult.builder()
                .success(allSuccess)
                .operationType(RelationOperationResult.OperationType.CREATE_RELATION)
                .successCount(successIds.size())
                .failedCount(failedIds.size())
                .successRecordIds(successIds)
                .failedRecordIds(failedIds)
                .errorMessage(allSuccess ? null : "Some relations failed to create")
                .build();
    }

    RelationOperationResult removeRelations(RelationDefinition relation, String recordId, List<String> targetRecordIds, Long tenantId) {
        if (relation.getRelationType() != RelationDefinition.RelationType.MANY_TO_MANY) {
            throw new MetaServiceException("removeRelations only supports MANY_TO_MANY relations.");
        }

        List<String> successIds = new ArrayList<>();
        List<String> failedIds = new ArrayList<>();

        for (String targetId : targetRecordIds) {
            try {
                Map<String, Object> conditions = new HashMap<>();
                conditions.put(relation.getSourceField(), recordId);
                conditions.put(relation.getTargetField(), targetId);
                conditions.put("tenant_id", tenantId);

                int deleted = dynamicDataMapper.delete(relation.getJoinTable(), conditions);
                if (deleted > 0) {
                    successIds.add(targetId);
                } else {
                    failedIds.add(targetId);
                }
            } catch (Exception e) {
                log.warn("Failed to remove relation for target {}: {}", logSafe(targetId), logSafe(e.getMessage()), e);
                failedIds.add(targetId);
            }
        }

        boolean allSuccess = failedIds.isEmpty();
        return RelationOperationResult.builder()
                .success(allSuccess)
                .operationType(RelationOperationResult.OperationType.REMOVE_RELATION)
                .successCount(successIds.size())
                .failedCount(failedIds.size())
                .successRecordIds(successIds)
                .failedRecordIds(failedIds)
                .errorMessage(allSuccess ? null : "Some relations failed to remove")
                .build();
    }

}
