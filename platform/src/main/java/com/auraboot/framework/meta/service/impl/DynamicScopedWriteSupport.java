package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.service.base.BaseMetaService;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.LogSanitizer;
import com.auraboot.framework.meta.service.DataDomainService;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.util.JsonbFieldHelper;
import com.auraboot.framework.meta.security.SqlSafetyUtils;
import com.auraboot.framework.meta.constant.SystemFieldConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Builds guarded UPDATE and DELETE statements with tenant, aggregate and permission scope. */
@Slf4j
@RequiredArgsConstructor
final class DynamicScopedWriteSupport extends BaseMetaService {
    private final DynamicDataMapper dynamicDataMapper;

    private final DataPermissionEngine dataPermissionEngine;

    private final DataDomainService dataDomainService;

    int executeScopedUpdate(
            ModelDefinition model,
            String modelCode,
            String primaryKeyColumn,
            String recordId,
            Map<String, Object> columnData,
            Set<String> jsonbColumns,
            Object expectedVersion){
        return executeScopedUpdate(
                model,
                modelCode,
                primaryKeyColumn,
                recordId,
                columnData,
                jsonbColumns,
                expectedVersion,
                null,
                null);
    }

    int executeScopedUpdate(
            ModelDefinition model,
            String modelCode,
            String primaryKeyColumn,
            String recordId,
            Map<String, Object> columnData,
            Set<String> jsonbColumns,
            Object expectedVersion,
            String compareColumn,
            Object compareValue){
        return executeScopedUpdate(model, modelCode, primaryKeyColumn, recordId, columnData, jsonbColumns, expectedVersion, compareColumn, compareValue, "update");
    }

    int executeScopedUpdate(ModelDefinition model, String modelCode, String primaryKeyColumn, String recordId, Map<String,Object> columnData, Set<String> jsonbColumns, Object expectedVersion, String compareColumn, Object compareValue, String permissionOperation){
        RecordCommandWriterGuard.assertInputAllowed(model, columnData, "update");
        if (columnData == null || columnData.isEmpty()) {
            throw new MetaServiceException("Update data cannot be empty");
        }

        String tableName = SqlSafetyUtils.requireIdentifier(model.getTableName(), "table name");
        String pkColumn = SqlSafetyUtils.requireIdentifier(primaryKeyColumn, "primary key column");
        Long tenantId = getCurrentTenantId();
        Long userId = getCurrentUserId();

        Map<String, Object> params = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder("UPDATE ")
                .append(tableName)
                .append(" SET ");
        int index = 0;
        for (Map.Entry<String, Object> entry : columnData.entrySet()) {
            String columnName = SqlSafetyUtils.requireIdentifier(entry.getKey(), "column name");
            if (index > 0) {
                sql.append(", ");
            }
            String paramName = "set" + index;
            if (jsonbColumns != null && jsonbColumns.contains(columnName)) {
                sql.append(columnName).append(" = #{params.").append(paramName)
                        .append(",jdbcType=OTHER,typeHandler=com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler}::jsonb");
            } else {
                sql.append(columnName).append(" = #{params.").append(paramName).append("}");
            }
            Object parameterValue = entry.getValue();
            // The SQL cast alone is not enough: MyBatis sees a Map first and
            // asks PostgreSQL for its hstore handler. Serialize every structured
            // JSONB value at this final binding chokepoint so regular JSON fields
            // and values produced by virtual-field merging behave identically.
            if (jsonbColumns != null && jsonbColumns.contains(columnName)
                    && parameterValue != null && !(parameterValue instanceof String)) {
                parameterValue = JsonbFieldHelper.toJsonString(parameterValue);
            }
            params.put(paramName, parameterValue);
            index++;
        }
        if (tableName.startsWith(SystemFieldConstants.DYNAMIC_TABLE_PREFIX)) {
            if (index > 0) {
                sql.append(", ");
            }
            // Every successful dynamic-model mutation advances the public optimistic token,
            // including legacy callers that do not yet submit an expectedVersion. When a trusted
            // expectedVersion is present the WHERE predicate below additionally turns this into
            // compare-and-swap. Externally managed ab_* tables retain their own version contracts.
            sql.append("row_version = row_version + 1");
        }

        params.put("recordId", recordId);
        params.put("tenantId", tenantId);
        sql.append(" WHERE ")
                .append(pkColumn)
                .append(" = #{params.recordId}")
                .append(" AND tenant_id = #{params.tenantId}");
        if (expectedVersion != null) {
            params.put("expectedVersion", expectedVersion);
            sql.append(" AND row_version = #{params.expectedVersion}");
        }
        if (compareColumn != null) {
            String guardedColumn = SqlSafetyUtils.requireIdentifier(
                    compareColumn, "compare-and-set column");
            params.put("compareValue", compareValue);
            sql.append(" AND ")
                    .append(guardedColumn)
                    .append(" IS NOT DISTINCT FROM #{params.compareValue}");
        }
        appendAggregateBindingGuard(sql, params, model);
        RecordCommandWriterGuard.appendStoredPredicate(sql, model, permissionOperation);
        RecordCommandWriterGuard.appendMarkerInvariant(sql, model, columnData);
        appendScopedWriteGuards(sql, tenantId, modelCode, userId, permissionOperation);

        return dynamicDataMapper.updateByQuery(sql.toString(), params);
    }

    int executeScopedDelete(
            ModelDefinition model,
            String modelCode,
            String primaryKeyColumn,
            String recordId,
            Long expectedVersion){
        String tableName = SqlSafetyUtils.requireIdentifier(model.getTableName(), "table name");
        String pkColumn = SqlSafetyUtils.requireIdentifier(primaryKeyColumn, "primary key column");
        Long tenantId = getCurrentTenantId();
        Long userId = getCurrentUserId();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("recordId", recordId);
        params.put("tenantId", tenantId);

        StringBuilder sql = new StringBuilder("DELETE FROM ")
                .append(tableName)
                .append(" WHERE ")
                .append(pkColumn)
                .append(" = #{params.recordId}")
                .append(" AND tenant_id = #{params.tenantId}");
        if (expectedVersion != null) {
            params.put("expectedVersion", expectedVersion);
            sql.append(" AND row_version = #{params.expectedVersion}");
        }
        appendAggregateBindingGuard(sql, params, model);
        RecordCommandWriterGuard.appendStoredPredicate(sql, model, "delete");
        appendScopedWriteGuards(sql, tenantId, modelCode, userId, "delete");

        return dynamicDataMapper.deleteByQuery(sql.toString(), params);
    }

    static void appendAggregateBindingGuard(StringBuilder sql, Map<String, Object> params, ModelDefinition model){
        String aggregateId = MetaContext.getCommandAggregateId();
        if (aggregateId == null || model == null) {
            return;
        }
        ModelDefinition.AggregateBinding binding = model.getAggregateBinding();
        if (binding == null || binding.getLocalField() == null || binding.getLocalField().isBlank()) {
            return;
        }
        String column = resolveBindingColumn(model, binding.getLocalField());
        params.put("authorizedAggregateId", aggregateId);
        sql.append(" AND ").append(column).append(" = #{params.authorizedAggregateId}");
    }

    static String resolveBindingColumn(ModelDefinition model, String fieldCode){
        String column = fieldCode;
        if (model.getFields() != null) {
            for (FieldDefinition field : model.getFields()) {
                if (fieldCode.equals(field.getCode()) && field.getColumnName() != null
                        && !field.getColumnName().isBlank()) {
                    column = field.getColumnName();
                    break;
                }
            }
        }
        return SqlSafetyUtils.requireIdentifier(column, "aggregate binding column");
    }

    void appendScopedWriteGuards(
            StringBuilder sql,
            Long tenantId,
            String modelCode,
            Long userId,
            String operation){
        String permitFilter = CommandPermitDataAccess.rowFilter(modelCode, userId);
        if (permitFilter != null) {
            appendScopedBulkFilter(sql, permitFilter);
            return;
        }

        try {
            String rowFilter = resolveWriteRowFilter(tenantId, modelCode, userId, operation);
            appendScopedBulkFilter(sql, rowFilter);
        } catch (Exception e) {
            log.error("Failed to apply row-level data permission for {} on model {} — denying access",
                    operation, logSafe(modelCode), e);
            throw new MetaServiceException("Data permission evaluation failed for model: " + modelCode, e);
        }

        try {
            String domainFilter = DynamicDataQueryScope.domainFilter(tenantId, modelCode, userId,
                    () -> dataDomainService.buildDomainFilter(modelCode, userId));
            appendScopedBulkFilter(sql, domainFilter);
        } catch (Exception e) {
            log.error("Failed to apply domain filter for {} on model {} — denying access",
                    operation, logSafe(modelCode), e);
            throw new MetaServiceException("Data domain filter evaluation failed for model: " + modelCode, e);
        }
    }

    String resolveWriteRowFilter(Long tenantId, String modelCode, Long userId){
        return resolveWriteRowFilter(tenantId, modelCode, userId, "update");
    }

    String resolveWriteRowFilter(Long tenantId, String modelCode, Long userId, String operation){
        String permitFilter = CommandPermitDataAccess.rowFilter(modelCode, userId);
        if (permitFilter != null) {
            return permitFilter;
        }
        return dataPermissionEngine.buildRowFilter(tenantId, modelCode, operation, userId);
    }

    void appendScopedBulkFilter(StringBuilder sql, String filter){
        if (filter == null || filter.isBlank()) {
            return;
        }
        String normalized = filter.trim();
        if (normalized.regionMatches(true, 0, "AND ", 0, 4)) {
            normalized = normalized.substring(4).trim();
        } else if (normalized.regionMatches(true, 0, "WHERE ", 0, 6)) {
            normalized = normalized.substring(6).trim();
        }
        if (normalized.isBlank()) {
            return;
        }
        rejectStatementInjectionMarkers(normalized);
        sql.append(" AND ").append(normalized);
    }

    void rejectStatementInjectionMarkers(String filter){
        if (filter.contains(";") || filter.contains("--") || filter.contains("/*") || filter.contains("*/")) {
            throw new MetaServiceException("Unsafe data scope filter for batch delete");
        }
    }

    private static String logSafe(Object value) {
        return LogSanitizer.safe(value);
    }
}
