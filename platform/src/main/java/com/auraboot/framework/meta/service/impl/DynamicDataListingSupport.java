package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.service.base.BaseMetaService;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.common.util.LogSanitizer;
import com.auraboot.framework.meta.service.DataDomainService;
import com.auraboot.framework.meta.service.FieldMaskService;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.dto.FieldMaskRule;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.executor.ExecutorRegistry;
import com.auraboot.framework.meta.service.executor.ModelDataExecutor;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.util.JsonbFieldHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/** Executes model and named-query listing, aggregate queries and statistics. */
@Slf4j
@RequiredArgsConstructor
final class DynamicDataListingSupport extends BaseMetaService {
    private static final String DEFAULT_LIST_SORT_COLUMN = "updated_at";

    private static final String DEFAULT_LIST_SORT_DIRECTION = "DESC";

    private final QueryBuilderService queryBuilderService;

    private final NamedQueryService namedQueryService;

    private final SecureSqlRewriter secureSqlRewriter;

    private final DynamicDataMapper dynamicDataMapper;

    private final DataPermissionEngine dataPermissionEngine;

    private final FieldMaskService fieldMaskService;

    private final DataDomainService dataDomainService;

    private final MetaModelMapper metaModelMapper;

    private final ExecutorRegistry executorRegistry;

    private final EnrichAuditUsersBeforeFieldPermissionFilterOperation enrichAuditUsersBeforeFieldPermissionFilterOperation;

    private final EnrichListRecordsOperation enrichListRecordsOperation;

    private final EnrichAuditUserDisplayFieldsOperation enrichAuditUserDisplayFieldsOperation;

    private final GetModelDefinitionOperation getModelDefinitionOperation;

    @FunctionalInterface
    interface EnrichAuditUsersBeforeFieldPermissionFilterOperation { List<Map<String, Object>> execute(
            String modelCode,
            List<Map<String, Object>> records,
            List<String> auditUserDisplayFields); }

    @FunctionalInterface
    interface EnrichListRecordsOperation { List<Map<String, Object>> execute(
            String modelCode,
            List<Map<String, Object>> records); }

    @FunctionalInterface
    interface EnrichAuditUserDisplayFieldsOperation { void execute(
            List<Map<String, Object>> records,
            List<String> requestedFields); }

    @FunctionalInterface
    interface GetModelDefinitionOperation { ModelDefinition execute(String modelCode); }

    private List<Map<String, Object>> enrichAuditUsersBeforeFieldPermissionFilter(
            String modelCode,
            List<Map<String, Object>> records,
            List<String> auditUserDisplayFields) { return enrichAuditUsersBeforeFieldPermissionFilterOperation.execute(modelCode,records,auditUserDisplayFields); }

    private List<Map<String, Object>> enrichListRecords(
            String modelCode,
            List<Map<String, Object>> records) { return enrichListRecordsOperation.execute(modelCode,records); }

    private void enrichAuditUserDisplayFields(
            List<Map<String, Object>> records,
            List<String> requestedFields) { enrichAuditUserDisplayFieldsOperation.execute(records,requestedFields); }

    private ModelDefinition getModelDefinition(String modelCode) { return getModelDefinitionOperation.execute(modelCode); }

    PaginationResult<Map<String, Object>> list(String modelCode, DynamicQueryRequest request){
        validateModelCode(modelCode);
        logOperation("list", modelCode, request);

        // 获取模型定义
        ModelDefinition model = getModelDefinition(modelCode);

        // Phase 1 virtual-model dispatch: if the model has a non-physical sourceType
        // AND an executor is registered for it, delegate. Otherwise fall through to
        // the existing physical-table inline path (preserves full backward compatibility).
        Optional<ModelDataExecutor> executorOpt = executorRegistry.resolve(model.getSourceType());
        if (executorOpt.isPresent()) {
            return executorOpt.get().list(modelCode, request);
        }

        // VIEW models have no physical table — delegate to NamedQuery
        if ("view".equals(model.getModelType())) {
            return listFromNamedQuery(resolveViewNamedQueryCode(modelCode), request);
        }

        // 构建查询
        QueryBuilderService.QueryBuilder queryBuilder = queryBuilderService.buildConditionQuery(
                model, request.getConditions());

        // Keyset pagination flag — when cursor is present, sort is forced to ORDER BY pid ASC
        boolean useCursor = request.getCursor() != null;

        // 添加排序 (skipped in cursor mode — cursor pagination requires ORDER BY pid ASC)
        if (!useCursor) {
            if (request.getSortFields() != null && !request.getSortFields().isEmpty()) {
                List<SortField> mappedSortFields = mapSortFields(model, request.getSortFields());
                queryBuilder = queryBuilderService.buildOrderQuery(queryBuilder, mappedSortFields, model);
            } else {
                queryBuilder.addOrderBy(DEFAULT_LIST_SORT_COLUMN, DEFAULT_LIST_SORT_DIRECTION);
            }
        }

        // 添加租户条件
        Long tenantId = getCurrentTenantId();
        queryBuilder.addCondition("tenant_id", QueryCondition.Operator.EQ.name(), tenantId);

        Long userId = getCurrentUserId();
        String permitRowFilter = CommandPermitDataAccess.rowFilter(modelCode, userId);
        boolean commandPermitInForce = permitRowFilter != null;
        String scopedRowFilter = null;
        String scopedDomainFilter = null;
        if (commandPermitInForce) {
            scopedRowFilter = permitRowFilter;
            if (!scopedRowFilter.isBlank()) {
                queryBuilder.addRawCondition(scopedRowFilter);
            }
        } else {
            // 添加数据权限行级过滤 — fail-secure: exception = deny all
            try {
                scopedRowFilter = DynamicDataQueryScope.rowFilter(tenantId, modelCode, userId,
                        () -> dataPermissionEngine.buildRowFilter(tenantId, modelCode, userId));
                if (scopedRowFilter != null && !scopedRowFilter.isBlank()) {
                    queryBuilder.addRawCondition(scopedRowFilter);
                }
            } catch (Exception e) {
                // codeql[java/log-injection] Model codes are validated metadata identifiers and are logged as structured parameters only.
                log.error("Failed to apply row-level data permission for model {} — returning empty result for security", logSafe(modelCode), e);
                throw new MetaServiceException("Data permission evaluation failed for model: " + modelCode, e);
            }
        }

        if (!commandPermitInForce) {
            // Apply data domain isolation filter (D5) — fail-secure
            try {
                scopedDomainFilter = DynamicDataQueryScope.domainFilter(tenantId, modelCode, userId,
                        () -> dataDomainService.buildDomainFilter(modelCode, userId));
                if (scopedDomainFilter != null && !scopedDomainFilter.isBlank()) {
                    queryBuilder.addRawCondition(scopedDomainFilter);
                }
            } catch (Exception e) {
                // codeql[java/log-injection] Model codes are validated metadata identifiers and are logged as structured parameters only.
                log.error("Failed to apply domain filter for model {} — returning empty result for security", logSafe(modelCode), e);
                throw new MetaServiceException("Data domain filter evaluation failed for model: " + modelCode, e);
            }
        }

        // Add keyword search across searchable fields
        if (request.getKeyword() != null && !request.getKeyword().isBlank()) {
            queryBuilder = queryBuilderService.buildKeywordSearch(queryBuilder, request.getKeyword(), model);
        }

        // Keyset (cursor-based) pagination: when cursor is provided, use WHERE pid > cursor
        // instead of OFFSET for O(1) deep pagination performance.
        if (useCursor) {
            queryBuilder.addCondition("pid", "GT", request.getCursor());
            // Force ORDER BY pid ASC for consistent public cursor traversal
            queryBuilder.addOrderBy("pid", "ASC");
            queryBuilder.setLimit(Math.min(request.getPageSize(), 1000));
        } else {
            // Traditional offset pagination
            PaginationRequest pageRequest = new PaginationRequest(
                    request.getPageNum(),
                    request.getPageSize(),
                    request.getKeyword()
            );
            queryBuilder = queryBuilderService.buildPaginationQuery(queryBuilder, pageRequest);
        }

        // 验证查询安全性
        QueryValidationResult validation = queryBuilderService.validateQuery(queryBuilder);
        if (!validation.isValid()) {
            throw new MetaServiceException("Query validation failed: " + validation.getErrorMessage());
        }

        // 手动添加租户ID条件到SQL和参数中
        String sql = queryBuilder.getSql();
        Map<String, Object> paramMap = queryBuilder.getParameterMap();

        // 执行查询
        List<Map<String, Object>> records = dynamicDataMapper.selectByQuery(sql, paramMap);

        // Read-shape contract: json/jsonb fields leave as JSON strings, never PGobject.
        JsonbFieldHelper.normalizeJsonReadValues(model, records);

        // Build count query with same filters (including row-level data permission)
        QueryBuilderService.QueryBuilder countBuilder = queryBuilderService.buildConditionQuery(
                model, request.getConditions());
        countBuilder.addCondition("tenant_id", QueryCondition.Operator.EQ.name(), tenantId);

        // Reuse the exact row-level filter from the data query so count cannot drift and
        // the same request does not re-run permission lookup for the count builder.
        if (scopedRowFilter != null && !scopedRowFilter.isBlank()) {
            countBuilder.addRawCondition(scopedRowFilter);
        }

        // Reuse the exact domain filter from the data query for count consistency and to
        // avoid duplicate domain metadata lookup in one list request.
        if (scopedDomainFilter != null && !scopedDomainFilter.isBlank()) {
            countBuilder.addRawCondition(scopedDomainFilter);
        }

        // Apply the same keyword search to count query for consistency
        if (request.getKeyword() != null && !request.getKeyword().isBlank()) {
            queryBuilderService.buildKeywordSearch(countBuilder, request.getKeyword(), model);
        }

        // Rewrite to count SQL
        String countSql = secureSqlRewriter.rewriteForCount(countBuilder.getSql());
        Map<String, Object> countParamMap = countBuilder.getParameterMap();

        Long total = dynamicDataMapper.countByQuery(countSql, countParamMap);

        if (!commandPermitInForce) {
            // 应用列级字段脱敏 (policy-based) — fail-secure: masking failure = deny access
            try {
                List<FieldMaskRule> maskRules = dataPermissionEngine.getFieldMaskRules(tenantId, modelCode, userId);
                if (maskRules != null && !maskRules.isEmpty()) {
                    records = dataPermissionEngine.applyFieldMasking(records, maskRules);
                }
            } catch (Exception e) {
                // codeql[java/log-injection] Model codes are validated metadata identifiers and are logged as structured parameters only.
                log.error("Failed to apply field masking for model {} — returning empty result for security", logSafe(modelCode), e);
                throw new MetaServiceException("Field masking evaluation failed for model: " + modelCode, e);
            }
        }

        if (!commandPermitInForce) {
            // Apply configurable field masking (A9) — fail-secure
            try {
                records = fieldMaskService.applyMaskingForList(modelCode, records, userId);
            } catch (Exception e) {
                // codeql[java/log-injection] Model codes are validated metadata identifiers and are logged as structured parameters only.
                log.error("Failed to apply configurable field masking for model {} — returning empty result for security", logSafe(modelCode), e);
                throw new MetaServiceException("Configurable field masking failed for model: " + modelCode, e);
            }
        }

        if (!commandPermitInForce) {
            // Resolve requested audit actors while the canonical created_by / updated_by IDs are
            // still available, then let field permissions remove those raw internal IDs. The
            // public controller exposes only the safe `<field>_display` projection.
            records = enrichAuditUsersBeforeFieldPermissionFilter(
                    modelCode, records, request.getAuditUserDisplayFields());
        }

        // Command handlers consume canonical stored values (ids/codes), not presentation-only
        // `<field>_display` projections. Their permit boundary was already evaluated by the
        // command pipeline, so target-reference authorization/enrichment here is both redundant
        // and a large N+1 multiplier for multi-step commands.
        if (!commandPermitInForce) {
            records = enrichListRecords(modelCode, records);
        }

        if (useCursor) {
            // Extract nextCursor from the last record's public pid.
            String nextCursor = null;
            if (!records.isEmpty()) {
                Object lastPid = records.get(records.size() - 1).get("pid");
                if (lastPid instanceof String pid && !pid.isBlank()) {
                    nextCursor = pid;
                }
            }
            return PaginationResult.ofCursor(
                    records,
                    total,
                    request.getPageSize(),
                    nextCursor
            );
        }

        return PaginationResult.of(
                records,
                total,
                request.getPageNum(),
                request.getPageSize()
        );
    }

    PaginationResult<Map<String, Object>> listByQueryCode(String queryCode, DynamicQueryRequest request){
        log.info("List by NamedQuery data source: queryCode={}", logSafe(queryCode));
        return listFromNamedQuery(queryCode, request);
    }

    String resolveViewNamedQueryCode(String modelCode){
        var modelEntity = metaModelMapper.findCurrentByCode(modelCode);
        if (modelEntity == null) {
            return modelCode;
        }
        Object namedQuery = modelEntity.getExtension() != null
                ? modelEntity.getExtension().get("namedQuery")
                : null;
        if (namedQuery == null) {
            return modelCode;
        }
        String code = namedQuery.toString().trim();
        return code.isEmpty() ? modelCode : code;
    }

    PaginationResult<Map<String, Object>> listFromNamedQuery(String queryCode, DynamicQueryRequest request){
        // codeql[java/log-injection] Named query codes are validated metadata identifiers and are logged as structured parameters only.
        log.debug("NamedQuery list: code={}", logSafe(queryCode));
        NamedQueryTestRequest nqRequest = new NamedQueryTestRequest();
        nqRequest.setPage(request.getPageNum());
        nqRequest.setSize(request.getPageSize());
        nqRequest.setExecuteQuery(true);

        ObjectMapper mapper = new ObjectMapper();

        // Pass through filter conditions
        if (request.getConditions() != null && !request.getConditions().isEmpty()) {
            var whereArray = mapper.createArrayNode();
            for (QueryCondition cond : request.getConditions()) {
                var node = mapper.createObjectNode();
                node.put("field", cond.getFieldName());
                node.put("operator", cond.getOperator().name().toLowerCase());
                if (cond.getOperator() == QueryCondition.Operator.IN || cond.getOperator() == QueryCondition.Operator.NOT_IN) {
                    node.set("value", mapper.valueToTree(cond.getValues() != null ? cond.getValues() : List.of()));
                } else if (cond.getOperator() == QueryCondition.Operator.BETWEEN) {
                    node.set("value", mapper.valueToTree(cond.getValues() != null ? cond.getValues() : List.of()));
                } else {
                    node.set("value", mapper.valueToTree(cond.getValue()));
                }
                whereArray.add(node);
            }
            nqRequest.setWhereConditions(whereArray);
        }

        // Pass through sort fields
        if (request.getSortFields() != null && !request.getSortFields().isEmpty()) {
            var orderArray = mapper.createArrayNode();
            for (SortField sf : request.getSortFields()) {
                var node = mapper.createObjectNode();
                node.put("field", sf.getFieldName());
                node.put("direction", sf.getDirection().name());
                orderArray.add(node);
            }
            nqRequest.setOrderConditions(orderArray);
        }

        PaginationResult<Map<String, Object>> result = namedQueryService.executeQuery(queryCode, nqRequest);
        enrichAuditUserDisplayFields(result.getRecords(), request.getAuditUserDisplayFields());
        return result;
    }

    List<Map<String, Object>> executeCustomQuery(String modelCode, String queryName, Map<String, Object> queryParams){
        validateModelCode(modelCode);
        logOperation("executeCustomQuery", modelCode, queryName);

        NamedQueryTestRequest testRequest = new NamedQueryTestRequest();
        testRequest.setParameters(queryParams != null ? queryParams : Collections.emptyMap());
        testRequest.setSize(1000);
        testRequest.setPage(1);

        PaginationResult<Map<String, Object>> result = namedQueryService.executeQuery(queryName, testRequest);
        return result.getRecords() != null ? result.getRecords() : Collections.emptyList();
    }

    Map<String, Object> aggregate(String modelCode, AggregateRequest aggregateRequest){
        validateModelCode(modelCode);
        logOperation("aggregate", modelCode, aggregateRequest);

        ModelDefinition model = getModelDefinition(modelCode);

        // Build aggregate query
        QueryBuilderService.QueryBuilder queryBuilder = queryBuilderService.buildAggregateQuery(model, aggregateRequest);

        // Add conditions if present
        if (aggregateRequest.getConditions() != null) {
            for (QueryCondition condition : aggregateRequest.getConditions()) {
                queryBuilder.addCondition(condition.getFieldName(), condition.getOperator().name(), condition.getValue());
            }
        }

        // Add tenant isolation
        Long tenantId = getCurrentTenantId();
        Long userId = getCurrentUserId();
        queryBuilder.addCondition("tenant_id", QueryCondition.Operator.EQ.name(), tenantId);

        // Row-level permission filter (fail-secure)
        try {
            String rowFilter = dataPermissionEngine.buildRowFilter(tenantId, modelCode, userId);
            if (rowFilter != null && !rowFilter.isBlank()) {
                queryBuilder.addRawCondition(rowFilter);
            }
        } catch (Exception e) {
            log.error("Failed to apply row-level data permission in aggregate for model {} — denying access", logSafe(modelCode), e);
            throw new MetaServiceException("Data permission evaluation failed for aggregate: " + modelCode, e);
        }

        // Domain isolation filter (fail-secure)
        try {
            String domainFilter = dataDomainService.buildDomainFilter(modelCode, userId);
            if (domainFilter != null && !domainFilter.isBlank()) {
                queryBuilder.addRawCondition(domainFilter);
            }
        } catch (Exception e) {
            log.error("Failed to apply domain filter in aggregate for model {} — denying access", logSafe(modelCode), e);
            throw new MetaServiceException("Data domain filter evaluation failed for aggregate: " + modelCode, e);
        }

        // Add GROUP BY if present
        String sql = queryBuilder.getSql();
        if (aggregateRequest.getGroupByFields() != null && !aggregateRequest.getGroupByFields().isEmpty()) {
            List<String> groupColumns = aggregateRequest.getGroupByFields().stream()
                    .map(f -> DynamicDataValueMapper.resolveColumnName(model, f))
                    .collect(Collectors.toList());
            sql = sql + " GROUP BY " + String.join(", ", groupColumns);
        }

        // Add limit
        if (aggregateRequest.getLimit() != null && aggregateRequest.getLimit() > 0) {
            sql = sql + " LIMIT " + aggregateRequest.getLimit();
        }

        Map<String, Object> paramMap = queryBuilder.getParameterMap();
        List<Map<String, Object>> results = dynamicDataMapper.selectByQuery(sql, paramMap);

        if (results == null || results.isEmpty()) {
            return Collections.emptyMap();
        }

        // If no GROUP BY, return the single aggregate row
        if (aggregateRequest.getGroupByFields() == null || aggregateRequest.getGroupByFields().isEmpty()) {
            return results.get(0);
        }

        // With GROUP BY, return all results in a wrapper
        Map<String, Object> response = new HashMap<>();
        response.put("groups", results);
        response.put("groupCount", results.size());
        return response;
    }

    Map<String, Object> getStats(String modelCode, Map<String, Object> statsParams){
        validateModelCode(modelCode);
        logOperation("getStats", modelCode, statsParams);

        // Parse stats params
        @SuppressWarnings("unchecked")
        List<String> fields = statsParams != null ? (List<String>) statsParams.get("fields") : null;
        @SuppressWarnings("unchecked")
        List<String> functions = statsParams != null ? (List<String>) statsParams.get("functions") : null;

        // Build AggregateRequest
        List<AggregateRequest.AggregateField> aggregateFields = new ArrayList<>();

        // Default: count all records
        aggregateFields.add(AggregateRequest.AggregateField.builder()
                .fieldName("*")
                .function(AggregateRequest.AggregateFunction.COUNT)
                .alias("total_count")
                .build());

        // Add requested field/function combinations
        if (fields != null && functions != null) {
            for (String field : fields) {
                for (String function : functions) {
                    String alias = function.toLowerCase() + "_" + field;
                    AggregateRequest.AggregateFunction aggFunc =
                            AggregateRequest.AggregateFunction.valueOf(function.toUpperCase());
                    aggregateFields.add(AggregateRequest.AggregateField.builder()
                            .fieldName(field)
                            .function(aggFunc)
                            .alias(alias)
                            .build());
                }
            }
        }

        @SuppressWarnings("unchecked")
        List<String> groupByFields = statsParams != null ? (List<String>) statsParams.get("groupBy") : null;

        AggregateRequest aggregateRequest = AggregateRequest.builder()
                .aggregateFields(aggregateFields)
                .groupByFields(groupByFields)
                .build();

        return aggregate(modelCode, aggregateRequest);
    }

    List<SortField> mapSortFields(ModelDefinition model, List<SortField> sortFields){
        return DynamicDataValueMapper.mapSortFields(model, sortFields);
    }

    private static String logSafe(Object value) {
        return LogSanitizer.safe(value);
    }
}
