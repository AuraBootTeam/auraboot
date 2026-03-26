package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.dto.*;
import java.util.List;
import java.util.Map;

/**
 * 查询构建服务
 * 职责：构建安全的、参数化的SQL查询
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
public interface QueryBuilderService {

    /**
     * 构建基础查询
     * @param modelDefinition 模型定义
     * @param queryType 查询类型
     * @return 查询构建器
     */
    QueryBuilder buildBaseQuery(ModelDefinition modelDefinition, QueryType queryType);

    /**
     * 构建条件查询
     * @param modelDefinition 模型定义
     * @param conditions 查询条件
     * @return 查询构建器
     */
    QueryBuilder buildConditionQuery(ModelDefinition modelDefinition, List<QueryCondition> conditions);

    /**
     * 构建分页查询
     * @param baseQuery 基础查询
     * @param pageRequest 分页请求
     * @return 分页查询构建器
     */
    QueryBuilder buildPaginationQuery(QueryBuilder baseQuery, PaginationRequest pageRequest);

    /**
     * Build keyword search conditions based on searchable fields.
     * Adds ILIKE conditions for the keyword across all searchable STRING/TEXT fields.
     * If no fields are marked searchable, falls back to all STRING/TEXT fields (up to 5).
     *
     * @param baseQuery the base query builder to add conditions to
     * @param keyword the search keyword (null/blank = no-op)
     * @param modelDefinition the model definition containing field metadata
     * @return the query builder with keyword conditions added
     */
    QueryBuilder buildKeywordSearch(QueryBuilder baseQuery, String keyword, ModelDefinition modelDefinition);

    /**
     * 构建排序查询
     * @param baseQuery 基础查询
     * @param sortFields 排序字段
     * @param modelDefinition 模型定义（用于字段名验证）
     * @return 排序查询构建器
     */
    QueryBuilder buildOrderQuery(QueryBuilder baseQuery, List<SortField> sortFields, ModelDefinition modelDefinition);

    /**
     * 构建聚合查询
     * @param modelDefinition 模型定义
     * @param aggregateRequest 聚合请求
     * @return 聚合查询构建器
     */
    QueryBuilder buildAggregateQuery(ModelDefinition modelDefinition, AggregateRequest aggregateRequest);

    /**
     * 构建关联查询
     * @param modelDefinition 主模型定义
     * @param relationDefinition 关联定义
     * @param conditions 查询条件
     * @return 关联查询构建器
     */
    QueryBuilder buildRelationQuery(ModelDefinition modelDefinition, 
                                   RelationDefinition relationDefinition, 
                                   List<QueryCondition> conditions);

    /**
     * 验证查询安全性
     * @param queryBuilder 查询构建器
     * @return 验证结果
     */
    QueryValidationResult validateQuery(QueryBuilder queryBuilder);

    /**
     * 查询构建器接口
     */
    interface QueryBuilder {
        String getSql();
        List<Object> getParameters();
        Map<String, Object> getParameterMap();
        QueryType getQueryType();
        String getTableName();
        List<String> getSelectFields();
        QueryBuilder addCondition(String field, String operator, Object value);
        QueryBuilder addRawCondition(String rawSql);
        /** Adds an OR group: (col1 ILIKE :pattern OR col2 ILIKE :pattern ...). Pattern must already include wildcards. */
        QueryBuilder addOrIlikeConditions(List<String> columns, String pattern);
        QueryBuilder addOrderBy(String field, String direction);
        QueryBuilder setLimit(int limit);
        QueryBuilder setOffset(int offset);
    }

    /**
     * Execute a raw parameterized SQL query and return rows.
     * Tenant isolation is enforced automatically via TenantLineInterceptor.
     */
    List<Map<String, Object>> executeRaw(String sql, Map<String, Object> params);

    /**
     * Verify that the physical table for the given table name exists.
     * Throws MetaServiceException if the table does not exist.
     */
    void verifyTableExists(String tableName, String modelCode);

    /**
     * 查询类型枚举
     */
    enum QueryType {
        SELECT, INSERT, UPDATE, DELETE, COUNT
    }
}