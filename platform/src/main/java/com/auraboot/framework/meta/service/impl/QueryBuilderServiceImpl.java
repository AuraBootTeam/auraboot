package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.QueryBuilderService;
import com.auraboot.framework.meta.service.base.BaseMetaService;
import com.auraboot.framework.meta.constant.SystemFieldConstants;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.exception.MetaServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 查询构建服务实现
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryBuilderServiceImpl extends BaseMetaService implements QueryBuilderService {

    private final DynamicDataMapper dynamicDataMapper;

    @Override
    public List<Map<String, Object>> executeRaw(String sql, Map<String, Object> params) {
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql, params);
        return rows != null ? rows : Collections.emptyList();
    }

    @Override
    public void verifyTableExists(String tableName, String modelCode) {
        try {
            dynamicDataMapper.selectByQuery("SELECT 1 FROM " + tableName + " LIMIT 0", Collections.emptyMap());
        } catch (Exception e) {
            throw new MetaServiceException("Table '" + tableName + "' does not exist for model '" + modelCode
                    + "'. The model may not be published or its table has not been created.");
        }
    }

    private static final Set<String> ALLOWED_OPERATORS = Set.of(
        "eq", "ne", "gt", "ge", "lt", "le",
        "like", "not_like", "in", "not_in",
        "is_null", "is_not_null", "between"
    );

    private static final Set<String> ALLOWED_SORT_DIRECTIONS = Set.of("asc", "desc");
    private static final Set<String> SYSTEM_FIELDS = SystemFieldConstants.QUERY_TRANSPARENT;

    /**
     * 将枚举操作符映射为SQL操作符
     */
    private static String mapOperatorToSql(String operator) {
        switch (operator.toLowerCase(Locale.ROOT)) {
            case "eq": return "=";
            case "ne": return "!=";
            case "gt": return ">";
            case "ge": return ">=";
            case "lt": return "<";
            case "le": return "<=";
            case "like": return "like";
            case "not_like": return "not like";
            case "in": return "IN";
            case "not_in": return "NOT IN";
            case "is_null": return "is null";
            case "is_not_null": return "is not null";
            case "between": return "between";
            default: return operator;
        }
    }

    @Override
    public QueryBuilder buildBaseQuery(ModelDefinition modelDefinition, QueryType queryType) {
        validateModelDefinition(modelDefinition);

        // INSERT和UPDATE暂不支持，应使用DynamicDataMapper直接操作
        if (queryType == QueryType.INSERT || queryType == QueryType.UPDATE) {
            throw new UnsupportedOperationException(
                "INSERT/UPDATE operations are not supported via QueryBuilder. " +
                "Please use DynamicDataMapper.insert() or DynamicDataMapper.update() instead.");
        }

        QueryBuilder builder = new QueryBuilderImpl(modelDefinition.getTableName(), queryType)
                .setSelectFields(getDefaultSelectFields(modelDefinition));

        // Auto-filter soft-deleted records for SELECT/COUNT queries
        if (modelDefinition.isSoftDelete() &&
                (queryType == QueryType.SELECT || queryType == QueryType.COUNT)) {
            builder.addCondition("deleted_flag", "EQ", false);
        }

        return builder;
    }

    @Override
    public QueryBuilder buildConditionQuery(ModelDefinition modelDefinition, List<QueryCondition> conditions) {
        QueryBuilder builder = buildBaseQuery(modelDefinition, QueryType.SELECT);
        
        if (conditions != null && !conditions.isEmpty()) {
            for (QueryCondition condition : conditions) {
                validateCondition(condition, modelDefinition);
                String columnName = resolveColumnName(modelDefinition, condition.getFieldName());
                Object normalizedValue = normalizeConditionValue(modelDefinition, condition);
                builder.addCondition(columnName, condition.getOperator().name(), normalizedValue);
            }
        }
        
        return builder;
    }

    @Override
    public QueryBuilder buildPaginationQuery(QueryBuilder baseQuery, PaginationRequest pageRequest) {
        if (pageRequest == null) {
            return baseQuery;
        }

        // 确保pageNum至少为1
        int pageNum = Math.max(1, pageRequest.getPageNum());
        int pageSize = Math.min(pageRequest.getPageSize(), 1000); // 限制最大页面大小
        int offset = (pageNum - 1) * pageSize;

        return baseQuery.setLimit(pageSize).setOffset(offset);
    }

    private static final Set<String> SEARCHABLE_DATA_TYPES = Set.of(
        "string", "text", "enum", "dict"
    );

    private static final int MAX_FALLBACK_SEARCHABLE_FIELDS = 5;

    @Override
    public QueryBuilder buildKeywordSearch(QueryBuilder baseQuery, String keyword, ModelDefinition modelDefinition) {
        if (keyword == null || keyword.isBlank() || modelDefinition == null || modelDefinition.getFields() == null) {
            return baseQuery;
        }

        // Collect explicitly searchable fields
        List<FieldDefinition> searchFields = modelDefinition.getFields().stream()
                .filter(f -> f.isSearchable() && !f.isTransientField() && !f.isComputedReadonly())
                .filter(f -> SEARCHABLE_DATA_TYPES.contains(f.getDataType() != null ? f.getDataType().toLowerCase(Locale.ROOT) : ""))
                .toList();

        // Fallback: if no fields are marked searchable, use first N STRING/TEXT fields
        if (searchFields.isEmpty()) {
            searchFields = modelDefinition.getFields().stream()
                    .filter(f -> !f.isTransientField() && !f.isComputedReadonly() && !f.isJsonbVirtual())
                    .filter(f -> SEARCHABLE_DATA_TYPES.contains(f.getDataType() != null ? f.getDataType().toLowerCase(Locale.ROOT) : ""))
                    .filter(f -> !SystemFieldConstants.QUERY_TRANSPARENT.contains(f.getCode()))
                    .filter(f -> !"pid".equals(f.getCode()))
                    .limit(MAX_FALLBACK_SEARCHABLE_FIELDS)
                    .toList();
        }

        if (searchFields.isEmpty()) {
            return baseQuery;
        }

        // Resolve column expressions for each searchable field
        List<String> columnExprs = new ArrayList<>();
        for (FieldDefinition field : searchFields) {
            if (field.isJsonbVirtual()) {
                columnExprs.add(field.getJsonbColumn() + "->>'" + field.getJsonbPath() + "'");
            } else {
                columnExprs.add(field.getColumnName());
            }
        }

        String likeValue = "%" + escapeIlike(keyword) + "%";

        // Use the internal QueryBuilderImpl method that supports keyword search
        if (baseQuery instanceof QueryBuilderImpl impl) {
            impl.addKeywordCondition(columnExprs, likeValue);
        } else {
            // codeql[java/log-injection] Keyword is logged as a structured diagnostic parameter only.
            log.warn("QueryBuilder implementation does not support keyword search, skipping keyword: {}", keyword);
        }

        return baseQuery;
    }

    @Override
    public QueryBuilder buildOrderQuery(QueryBuilder baseQuery, List<SortField> sortFields, ModelDefinition modelDefinition) {
        if (sortFields != null && !sortFields.isEmpty()) {
            for (SortField sortField : sortFields) {
                validateSortField(sortField);

                // 验证字段名并解析为列名
                String columnName = resolveColumnName(modelDefinition, sortField.getFieldName());

                // 额外安全检查：拒绝包含危险字符的列名
                if (columnName.matches(".*[\\s;'\"].*")) {
                    throw new MetaServiceException("Invalid sort field contains dangerous characters: " + sortField.getFieldName());
                }

                baseQuery.addOrderBy(columnName, sortField.getDirection().name());
            }
        }

        return baseQuery;
    }

    @Override
    public QueryBuilder buildAggregateQuery(ModelDefinition modelDefinition, AggregateRequest aggregateRequest) {
        validateModelDefinition(modelDefinition);
        
        QueryBuilderImpl builder = new QueryBuilderImpl(modelDefinition.getTableName(), QueryType.SELECT);
        
        // 构建聚合字段
        List<String> selectFields = new ArrayList<>();
        if (aggregateRequest.getGroupByFields() != null) {
            for (String fieldName : aggregateRequest.getGroupByFields()) {
                selectFields.add(resolveColumnName(modelDefinition, fieldName));
            }
        }
        
        if (aggregateRequest.getAggregateFields() != null) {
            for (AggregateRequest.AggregateField field : aggregateRequest.getAggregateFields()) {
                String fieldName = field.getFieldName();
                String aggregateExpr;
                if ("*".equals(fieldName) && field.getFunction() == AggregateRequest.AggregateFunction.COUNT) {
                    aggregateExpr = "COUNT(*) AS " + field.getAlias();
                } else {
                    String columnName = resolveColumnName(modelDefinition, fieldName);
                    aggregateExpr = field.getFunction() + "(" + columnName + ") AS " + field.getAlias();
                }
                selectFields.add(aggregateExpr);
            }
        }
        
        builder.setSelectFields(selectFields);
        
        return builder;
    }

    @Override
    public QueryBuilder buildRelationQuery(ModelDefinition modelDefinition, 
                                         RelationDefinition relationDefinition, 
                                         List<QueryCondition> conditions) {
        validateModelDefinition(modelDefinition);
        validateRelationDefinition(relationDefinition);
        
        QueryBuilderImpl builder = new QueryBuilderImpl(modelDefinition.getTableName(), QueryType.SELECT);
        
        // 添加JOIN子句
        String joinClause = buildJoinClause(relationDefinition);
        builder.addJoin(joinClause);
        
        // 添加条件
        if (conditions != null) {
            for (QueryCondition condition : conditions) {
                String columnName = resolveColumnName(modelDefinition, condition.getFieldName());
                builder.addCondition(columnName, condition.getOperator().name(), condition.getValue());
            }
        }
        
        return builder;
    }

    @Override
    public QueryValidationResult validateQuery(QueryBuilder queryBuilder) {
        try {
            String sql = queryBuilder.getSql();
            List<Object> parameters = queryBuilder.getParameters();
            
            // 基本SQL注入检查
            if (containsSqlInjectionPatterns(sql)) {
                return QueryValidationResult.invalid("Potential SQL injection detected");
            }
            
            // 参数数量检查
            long parameterPlaceholders = countNamedParameters(sql);
            if (parameterPlaceholders != parameters.size()) {
                return QueryValidationResult.invalid("Parameter count mismatch");
            }
            
            return QueryValidationResult.valid();
            
        } catch (Exception e) {
            return QueryValidationResult.invalid("Query validation failed: " + e.getMessage());
        }
    }

    /**
     * 查询构建器实现类
     */
    private static class QueryBuilderImpl implements QueryBuilder {
        private final String tableName;
        private final QueryType queryType;
        private List<String> selectFields = new ArrayList<>();
        private final List<String> conditions = new ArrayList<>();
        private final List<Object> parameters = new ArrayList<>();
        private final List<String> orderByFields = new ArrayList<>();
        private final List<String> joins = new ArrayList<>();
        private Integer limit;
        private Integer offset;

        public QueryBuilderImpl(String tableName, QueryType queryType) {
            this.tableName = tableName;
            this.queryType = queryType;
        }

        @Override
        public String getSql() {
            StringBuilder sql = new StringBuilder();
            
            switch (queryType) {
                case SELECT:
                    buildSelectSql(sql);
                    break;
                case COUNT:
                    buildCountSql(sql);
                    break;
                case INSERT:
                    buildInsertSql(sql);
                    break;
                case UPDATE:
                    buildUpdateSql(sql);
                    break;
                case DELETE:
                    buildDeleteSql(sql);
                    break;
            }
            
            return sql.toString();
        }

        @Override
        public List<Object> getParameters() {
            return new ArrayList<>(parameters);
        }

        @Override
        public Map<String, Object> getParameterMap() {
            Map<String, Object> paramMap = new HashMap<>();
            for (int i = 0; i < parameters.size(); i++) {
                paramMap.put("param" + i, parameters.get(i));
            }
            return paramMap;
        }

        @Override
        public QueryType getQueryType() {
            return queryType;
        }

        @Override
        public String getTableName() {
            return tableName;
        }

        @Override
        public List<String> getSelectFields() {
            return new ArrayList<>(selectFields);
        }

        @Override
        public QueryBuilder addCondition(String field, String operator, Object value) {
            if (field != null && operator != null) {
                String sqlOperator = mapOperatorToSql(operator);
                
                // Handle IN and NOT_IN operators specially
                if ("in".equalsIgnoreCase(operator) || "not_in".equalsIgnoreCase(operator)) {
                    handleInOperator(field, sqlOperator, value);
                } else if ("between".equalsIgnoreCase(operator)) {
                    if (value instanceof List<?> list && list.size() >= 2) {
                        String fromParam = "param" + parameters.size();
                        parameters.add(list.get(0));
                        String toParam = "param" + parameters.size();
                        parameters.add(list.get(1));
                        conditions.add(field + " BETWEEN #{params." + fromParam + "} AND #{params." + toParam + "}");
                    }
                } else if ("is_null".equalsIgnoreCase(operator) || "is_not_null".equalsIgnoreCase(operator)) {
                    // NULL operators don't need parameters
                    conditions.add(field + " " + sqlOperator);
                    return this; // Don't add parameter for NULL checks
                } else {
                    String paramName = "param" + parameters.size();
                    conditions.add(field + " " + sqlOperator + " #{params." + paramName + "}");
                    parameters.add(value);
                }
            }
            return this;
        }
        
        /**
         * 处理IN和NOT_IN操作符
         * 将List参数展开为多个占位符: field IN (?, ?, ?)
         */
        private void handleInOperator(String field, String sqlOperator, Object value) {
            if (value == null) {
                // 空值处理: 生成恒假条件 (IN) 或恒真条件 (NOT_IN)
                if ("in".equalsIgnoreCase(sqlOperator)) {
                    conditions.add("1=0"); // IN (NULL) -> 恒假
                } else {
                    conditions.add("1=1"); // NOT IN (NULL) -> 恒真
                }
                return;
            }
            
            List<?> valueList;
            if (value instanceof List) {
                valueList = (List<?>) value;
            } else if (value instanceof Collection) {
                valueList = new ArrayList<>((Collection<?>) value);
            } else {
                // 单个值,转换为List
                valueList = Collections.singletonList(value);
            }
            
            // 空List处理
            if (valueList.isEmpty()) {
                if ("in".equalsIgnoreCase(sqlOperator)) {
                    conditions.add("1=0"); // IN () -> 恒假
                } else {
                    conditions.add("1=1"); // NOT IN () -> 恒真
                }
                return;
            }
            
            // 生成多个占位符
            List<String> placeholders = new ArrayList<>();
            for (Object item : valueList) {
                String paramName = "param" + parameters.size();
                placeholders.add("#{params." + paramName + "}");
                parameters.add(item);
            }
            
            // 构建IN条件: field IN (?, ?, ?)
            String inCondition = field + " " + sqlOperator + " (" + String.join(", ", placeholders) + ")";
            conditions.add(inCondition);
        }

        @Override
        public QueryBuilder addRawCondition(String rawSql) {
            if (rawSql != null && !rawSql.isBlank()) {
                conditions.add(rawSql);
            }
            return this;
        }

        @Override
        public QueryBuilder addOrIlikeConditions(List<String> columns, String pattern) {
            if (columns == null || columns.isEmpty() || pattern == null) return this;
            StringBuilder sb = new StringBuilder("(");
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) sb.append(" OR ");
                String paramName = "param" + parameters.size();
                sb.append(columns.get(i)).append(" ILIKE #{params.").append(paramName).append("} ESCAPE '\\\\'");
                parameters.add(pattern);
            }
            sb.append(")");
            conditions.add(sb.toString());
            return this;
        }

        @Override
        public QueryBuilder addOrderBy(String field, String direction) {
            if (field != null && direction != null) {
                orderByFields.add(field + " " + direction);
            }
            return this;
        }

        @Override
        public QueryBuilder setLimit(int limit) {
            this.limit = limit;
            return this;
        }

        @Override
        public QueryBuilder setOffset(int offset) {
            this.offset = offset;
            return this;
        }

        public QueryBuilderImpl setSelectFields(List<String> fields) {
            this.selectFields = fields != null ? new ArrayList<>(fields) : new ArrayList<>();
            return this;
        }

        public void addJoin(String joinClause) {
            if (joinClause != null && !joinClause.trim().isEmpty()) {
                joins.add(joinClause);
            }
        }

        /**
         * Add a keyword search condition across multiple columns using ILIKE with OR.
         * All columns share the same parameter value.
         */
        public void addKeywordCondition(List<String> columnExprs, String likeValue) {
            if (columnExprs == null || columnExprs.isEmpty() || likeValue == null) {
                return;
            }
            // Each column gets its own parameter reference but the same value
            List<String> ilikeClauses = new ArrayList<>();
            for (String columnExpr : columnExprs) {
                String paramName = "param" + parameters.size();
                ilikeClauses.add(columnExpr + " ILIKE #{params." + paramName + "}");
                parameters.add(likeValue);
            }
            conditions.add("(" + String.join(" OR ", ilikeClauses) + ")");
        }

        private void buildSelectSql(StringBuilder sql) {
            sql.append("select ");
            if (selectFields.isEmpty()) {
                sql.append("*");
            } else {
                sql.append(String.join(", ", selectFields));
            }
            sql.append(" from ").append(tableName);
            
            // 添加JOIN
            for (String join : joins) {
                sql.append(" ").append(join);
            }
            
            // 添加WHERE条件
            if (!conditions.isEmpty()) {
                sql.append(" where ").append(String.join(" and ", conditions));
            }
            
            // 添加ORDER BY
            if (!orderByFields.isEmpty()) {
                sql.append(" ORDER BY ").append(String.join(", ", orderByFields));
            }
            
            // 添加LIMIT和OFFSET
            if (limit != null) {
                sql.append(" LIMIT ").append(limit);
                if (offset != null) {
                    sql.append(" OFFSET ").append(offset);
                }
            }
        }

        private void buildCountSql(StringBuilder sql) {
            sql.append("select count(*) from ").append(tableName);
            
            // 添加JOIN
            for (String join : joins) {
                sql.append(" ").append(join);
            }
            
            // 添加WHERE条件
            if (!conditions.isEmpty()) {
                sql.append(" where ").append(String.join(" and ", conditions));
            }
        }

        private void buildInsertSql(StringBuilder sql) {
            throw new UnsupportedOperationException("INSERT SQL building is not supported");
        }

        private void buildUpdateSql(StringBuilder sql) {
            throw new UnsupportedOperationException("UPDATE SQL building is not supported");
        }

        private void buildDeleteSql(StringBuilder sql) {
            sql.append("delete from ").append(tableName);
            if (!conditions.isEmpty()) {
                sql.append(" where ").append(String.join(" and ", conditions));
            }
        }
    }

    // 辅助方法
    private void validateModelDefinition(ModelDefinition modelDefinition) {
        if (modelDefinition == null) {
            throw new MetaServiceException("Model definition cannot be null");
        }
        if (modelDefinition.getTableName() == null || modelDefinition.getTableName().trim().isEmpty()) {
            throw new MetaServiceException("Table name cannot be null or empty");
        }
    }

    private void validateCondition(QueryCondition condition, ModelDefinition modelDefinition) {
        if (condition == null) {
            return;
        }

        // 验证operator不为null
        if (condition.getOperator() == null) {
            throw new MetaServiceException("Operator cannot be null for field: " + condition.getFieldName());
        }

        if (!ALLOWED_OPERATORS.contains(condition.getOperator().name().toLowerCase(Locale.ROOT))) {
            throw new MetaServiceException("Invalid operator: " + condition.getOperator());
        }
        
        // 验证字段是否存在于模型中（或系统字段）
        resolveColumnName(modelDefinition, condition.getFieldName());
    }

    private void validateSortField(SortField sortField) {
        if (sortField == null) {
            return;
        }
        
        if (!ALLOWED_SORT_DIRECTIONS.contains(sortField.getDirection().name().toLowerCase())) {
            throw new MetaServiceException("Invalid sort direction: " + sortField.getDirection());
        }
    }

    private void validateRelationDefinition(RelationDefinition relationDefinition) {
        if (relationDefinition == null) {
            throw new MetaServiceException("Relation definition cannot be null");
        }
    }

    private List<String> getDefaultSelectFields(ModelDefinition modelDefinition) {
        if (modelDefinition.getFields() == null || modelDefinition.getFields().isEmpty()) {
            return List.of("*");
        }

        return modelDefinition.getFields().stream()
                .filter(f -> !f.isTransientField()) // Skip transient fields from SELECT
                .map(field -> {
                    if (field.isJsonbVirtual()) {
                        // Extract JSONB path with type cast and alias
                        return field.getJsonbSelectExpression() + " AS " + field.getCode();
                    }
                    if (field.isComputedReadonly() && field.getComputeExpression() != null) {
                        // Inject SQL expression with alias for computed readonly fields
                        return "(" + field.getComputeExpression() + ") AS " + field.getColumnName();
                    }
                    return field.getColumnName();
                })
                .collect(Collectors.toList());
    }

    private String resolveColumnName(ModelDefinition modelDefinition, String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            throw new MetaServiceException("Field name cannot be null or empty");
        }
        if (SYSTEM_FIELDS.contains(fieldName)) {
            return fieldName;
        }
        if (modelDefinition.getFields() == null) {
            throw new MetaServiceException("Model fields not loaded for: " + modelDefinition.getCode());
        }
        for (FieldDefinition field : modelDefinition.getFields()) {
            if (fieldName.equals(field.getCode()) || fieldName.equals(field.getColumnName())) {
                // JSONB virtual fields use their typed expression for WHERE/ORDER BY
                if (field.isJsonbVirtual()) {
                    return field.getJsonbFilterExpression();
                }
                return field.getColumnName();
            }
        }
        throw new MetaServiceException("Field not found in model: " + fieldName);
    }

    private Object normalizeConditionValue(ModelDefinition modelDefinition, QueryCondition condition) {
        if (condition == null || condition.getOperator() == null) {
            return null;
        }
        String fieldName = condition.getFieldName();
        if (fieldName == null) {
            return condition.getValue();
        }
        if (SYSTEM_FIELDS.contains(fieldName)) {
            return normalizeSystemFieldValue(fieldName, condition);
        }

        FieldDefinition fieldDef = null;
        if (modelDefinition.getFields() != null) {
            for (FieldDefinition field : modelDefinition.getFields()) {
                if (fieldName.equals(field.getCode()) || fieldName.equals(field.getColumnName())) {
                    fieldDef = field;
                    break;
                }
            }
        }
        if (fieldDef == null || fieldDef.getDataType() == null) {
            return condition.getValue();
        }

        if (condition.getOperator() == QueryCondition.Operator.IN || condition.getOperator() == QueryCondition.Operator.NOT_IN) {
            Object values = condition.getValues() != null ? condition.getValues() : condition.getValue();
            if (values instanceof Collection<?> collection) {
                List<Object> converted = new ArrayList<>(collection.size());
                for (Object value : collection) {
                    converted.add(convertValueByDataType(fieldDef.getDataType(), value));
                }
                return converted;
            }
            return convertValueByDataType(fieldDef.getDataType(), condition.getValue());
        }

        if (condition.getOperator() == QueryCondition.Operator.BETWEEN && condition.getValues() != null) {
            List<Object> converted = new ArrayList<>(condition.getValues().size());
            for (Object value : condition.getValues()) {
                converted.add(convertValueByDataType(fieldDef.getDataType(), value));
            }
            return converted;
        }

        return convertValueByDataType(fieldDef.getDataType(), condition.getValue());
    }

    private Object normalizeSystemFieldValue(String fieldName, QueryCondition condition) {
        if (condition.getOperator() == QueryCondition.Operator.IN || condition.getOperator() == QueryCondition.Operator.NOT_IN) {
            Object values = condition.getValues() != null ? condition.getValues() : condition.getValue();
            if (values instanceof Collection<?> collection) {
                List<Object> converted = new ArrayList<>(collection.size());
                for (Object value : collection) {
                    converted.add(convertSystemFieldValue(fieldName, value));
                }
                return converted;
            }
            return convertSystemFieldValue(fieldName, condition.getValue());
        }

        if (condition.getOperator() == QueryCondition.Operator.BETWEEN && condition.getValues() != null) {
            List<Object> converted = new ArrayList<>(condition.getValues().size());
            for (Object value : condition.getValues()) {
                converted.add(convertSystemFieldValue(fieldName, value));
            }
            return converted;
        }

        return convertSystemFieldValue(fieldName, condition.getValue());
    }

    private Object convertSystemFieldValue(String fieldName, Object value) {
        if (value == null) {
            return null;
        }
        String normalizedField = fieldName.toLowerCase(Locale.ROOT);
        return switch (normalizedField) {
            case "tenant_id", "created_by", "updated_by" -> convertValueByDataType("long", value);
            case "created_at", "updated_at" -> convertSystemTemporalValue(value);
            default -> value;
        };
    }

    private Object convertSystemTemporalValue(Object value) {
        if (!(value instanceof String str)) {
            return value;
        }
        if (str.isBlank()) {
            return null;
        }
        try {
            if (str.length() == 10) {
                return java.sql.Timestamp.valueOf(java.time.LocalDate.parse(str).atStartOfDay());
            }
            return java.sql.Timestamp.valueOf(java.time.LocalDateTime.parse(str));
        } catch (Exception ex) {
            log.debug("System temporal condition value conversion failed: value={}", value);
            return value;
        }
    }

    private Object convertValueByDataType(String dataType, Object value) {
        if (value == null || dataType == null) {
            return value;
        }
        if (!(value instanceof String str)) {
            return value;
        }
        if (str.isBlank()) {
            return null;
        }
        String dt = dataType.toLowerCase(Locale.ROOT);
        try {
            return switch (dt) {
                case "integer", "int" -> Integer.valueOf(str);
                case "long", "bigint" -> Long.valueOf(str);
                case "decimal", "numeric", "float", "double" -> new java.math.BigDecimal(str);
                case "boolean", "bool" -> Boolean.valueOf(str);
                case "date" -> java.sql.Date.valueOf(java.time.LocalDate.parse(str));
                case "timestamp", "datetime" -> java.sql.Timestamp.valueOf(java.time.LocalDateTime.parse(str));
                default -> value;
            };
        } catch (Exception ex) {
            log.debug("Condition value type conversion failed: dataType={}, value={}", dataType, value);
            return value;
        }
    }

    private long countNamedParameters(String sql) {
        long count = 0;
        int index = 0;
        while ((index = sql.indexOf("#{params.", index)) >= 0) {
            count++;
            index += 9;
        }
        return count;
    }

    private String buildJoinClause(RelationDefinition relationDefinition) {
        // 简化的JOIN构建，实际实现需要根据关联类型构建
        return String.format("LEFT JOIN %s ON %s.%s = %s.%s",
                relationDefinition.getTargetTable(),
                relationDefinition.getSourceTable(),
                relationDefinition.getSourceField(),
                relationDefinition.getTargetTable(),
                relationDefinition.getTargetField());
    }

    /**
     * Escape special characters in ILIKE patterns to prevent unintended wildcard matching.
     */
    private static String escapeIlike(String input) {
        if (input == null) return null;
        return input
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private boolean containsSqlInjectionPatterns(String sql) {
        String upperSql = sql.toUpperCase();
        String[] dangerousPatterns = {
            "DROP TABLE", "DELETE FROM", "truncate", "ALTER TABLE",
            "CREATE TABLE", "INSERT INTO", "UPDATE SET",
            "UNION SELECT", "OR 1=1", "AND 1=1", "--", "/*", "*/"
        };
        
        for (String pattern : dangerousPatterns) {
            if (upperSql.contains(pattern)) {
                return true;
            }
        }
        
        return false;
    }
}
