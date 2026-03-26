package com.auraboot.framework.meta.dto;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 存储查询请求DTO
 * 用于封装存储查询的请求参数
 * 
 * @author AuraBoot Team
 * @since 1.0.0
 */
public class StorageQueryRequest {
    
    /**
     * 查询字段列表（为空则查询所有字段）
     */
    private List<String> selectFields;
    
    /**
     * 查询条件列表
     */
    private List<QueryCondition> conditions;
    
    /**
     * 排序条件列表
     */
    private List<OrderCondition> orderBy;
    
    /**
     * 分组字段列表
     */
    private List<String> groupBy;
    
    /**
     * 分组条件
     */
    private List<QueryCondition> having;
    
    /**
     * 关联查询配置
     */
    private List<JoinConfig> joins;
    
    /**
     * 查询参数
     */
    private Map<String, Object> parameters;
    
    /**
     * 是否去重
     */
    private boolean distinct;
    
    /**
     * 限制返回记录数
     */
    private Integer limit;
    
    /**
     * 偏移量
     */
    private Integer offset;
    
    /**
     * 查询提示（用于数据库优化）
     */
    private String queryHint;
    
    /**
     * 是否包含软删除的记录
     */
    private boolean includeDeleted;
    
    /**
     * 租户ID（多租户查询）
     */
    private String tenantId;
    
    /**
     * 聚合字段列表
     */
    private List<AggregateField> aggregateFields;
    
    public StorageQueryRequest() {
        this.selectFields = new ArrayList<>();
        this.conditions = new ArrayList<>();
        this.orderBy = new ArrayList<>();
        this.groupBy = new ArrayList<>();
        this.having = new ArrayList<>();
        this.joins = new ArrayList<>();
        this.parameters = new HashMap<>();
        this.aggregateFields = new ArrayList<>();
        this.distinct = false;
        this.includeDeleted = false;
    }
    
    /**
     * 添加查询字段
     * 
     * @param field 字段名
     * @return 当前对象
     */
    public StorageQueryRequest select(String field) {
        this.selectFields.add(field);
        return this;
    }
    
    /**
     * 添加查询字段列表
     * 
     * @param fields 字段名列表
     * @return 当前对象
     */
    public StorageQueryRequest select(List<String> fields) {
        this.selectFields.addAll(fields);
        return this;
    }
    
    /**
     * 添加等值条件
     * 
     * @param field 字段名
     * @param value 值
     * @return 当前对象
     */
    public StorageQueryRequest eq(String field, Object value) {
        this.conditions.add(new QueryCondition(field, QueryOperator.EQ, value));
        return this;
    }
    
    /**
     * 添加不等值条件
     * 
     * @param field 字段名
     * @param value 值
     * @return 当前对象
     */
    public StorageQueryRequest ne(String field, Object value) {
        this.conditions.add(new QueryCondition(field, QueryOperator.NE, value));
        return this;
    }
    
    /**
     * 添加大于条件
     * 
     * @param field 字段名
     * @param value 值
     * @return 当前对象
     */
    public StorageQueryRequest gt(String field, Object value) {
        this.conditions.add(new QueryCondition(field, QueryOperator.GT, value));
        return this;
    }
    
    /**
     * 添加大于等于条件
     * 
     * @param field 字段名
     * @param value 值
     * @return 当前对象
     */
    public StorageQueryRequest ge(String field, Object value) {
        this.conditions.add(new QueryCondition(field, QueryOperator.GE, value));
        return this;
    }
    
    /**
     * 添加小于条件
     * 
     * @param field 字段名
     * @param value 值
     * @return 当前对象
     */
    public StorageQueryRequest lt(String field, Object value) {
        this.conditions.add(new QueryCondition(field, QueryOperator.LT, value));
        return this;
    }
    
    /**
     * 添加小于等于条件
     * 
     * @param field 字段名
     * @param value 值
     * @return 当前对象
     */
    public StorageQueryRequest le(String field, Object value) {
        this.conditions.add(new QueryCondition(field, QueryOperator.LE, value));
        return this;
    }
    
    /**
     * 添加模糊查询条件
     * 
     * @param field 字段名
     * @param value 值
     * @return 当前对象
     */
    public StorageQueryRequest like(String field, Object value) {
        this.conditions.add(new QueryCondition(field, QueryOperator.LIKE, value));
        return this;
    }
    
    /**
     * 添加IN条件
     * 
     * @param field 字段名
     * @param values 值列表
     * @return 当前对象
     */
    public StorageQueryRequest in(String field, List<Object> values) {
        this.conditions.add(new QueryCondition(field, QueryOperator.IN, values));
        return this;
    }
    
    /**
     * 添加NOT IN条件
     * 
     * @param field 字段名
     * @param values 值列表
     * @return 当前对象
     */
    public StorageQueryRequest notIn(String field, List<Object> values) {
        this.conditions.add(new QueryCondition(field, QueryOperator.NOT_IN, values));
        return this;
    }
    
    /**
     * 添加IS NULL条件
     * 
     * @param field 字段名
     * @return 当前对象
     */
    public StorageQueryRequest isNull(String field) {
        this.conditions.add(new QueryCondition(field, QueryOperator.IS_NULL, null));
        return this;
    }
    
    /**
     * 添加IS NOT NULL条件
     * 
     * @param field 字段名
     * @return 当前对象
     */
    public StorageQueryRequest isNotNull(String field) {
        this.conditions.add(new QueryCondition(field, QueryOperator.IS_NOT_NULL, null));
        return this;
    }
    
    /**
     * 添加BETWEEN条件
     * 
     * @param field 字段名
     * @param start 开始值
     * @param end 结束值
     * @return 当前对象
     */
    public StorageQueryRequest between(String field, Object start, Object end) {
        List<Object> values = new ArrayList<>();
        values.add(start);
        values.add(end);
        this.conditions.add(new QueryCondition(field, QueryOperator.BETWEEN, values));
        return this;
    }
    
    /**
     * 添加升序排序
     * 
     * @param field 字段名
     * @return 当前对象
     */
    public StorageQueryRequest orderByAsc(String field) {
        this.orderBy.add(new OrderCondition(field, OrderDirection.ASC));
        return this;
    }
    
    /**
     * 添加降序排序
     * 
     * @param field 字段名
     * @return 当前对象
     */
    public StorageQueryRequest orderByDesc(String field) {
        this.orderBy.add(new OrderCondition(field, OrderDirection.DESC));
        return this;
    }
    
    /**
     * 添加分组字段
     * 
     * @param field 字段名
     * @return 当前对象
     */
    public StorageQueryRequest groupBy(String field) {
        this.groupBy.add(field);
        return this;
    }
    
    /**
     * 设置限制记录数
     * 
     * @param limit 限制数
     * @return 当前对象
     */
    public StorageQueryRequest limit(int limit) {
        this.limit = limit;
        return this;
    }
    
    /**
     * 设置偏移量
     * 
     * @param offset 偏移量
     * @return 当前对象
     */
    public StorageQueryRequest offset(int offset) {
        this.offset = offset;
        return this;
    }
    
    /**
     * 设置去重
     * 
     * @return 当前对象
     */
    public StorageQueryRequest distinct() {
        this.distinct = true;
        return this;
    }
    
    /**
     * 包含软删除记录
     * 
     * @return 当前对象
     */
    public StorageQueryRequest includeDeleted() {
        this.includeDeleted = true;
        return this;
    }
    
    /**
     * 设置租户ID
     * 
     * @param tenantId 租户ID
     * @return 当前对象
     */
    public StorageQueryRequest tenantId(String tenantId) {
        this.tenantId = tenantId;
        return this;
    }
    
    // Getters and Setters
    public List<String> getSelectFields() {
        return selectFields;
    }
    
    public void setSelectFields(List<String> selectFields) {
        this.selectFields = selectFields;
    }
    
    public List<QueryCondition> getConditions() {
        return conditions;
    }
    
    public void setConditions(List<QueryCondition> conditions) {
        this.conditions = conditions;
    }
    
    public List<OrderCondition> getOrderBy() {
        return orderBy;
    }
    
    public void setOrderBy(List<OrderCondition> orderBy) {
        this.orderBy = orderBy;
    }
    
    public List<String> getGroupBy() {
        return groupBy;
    }
    
    public void setGroupBy(List<String> groupBy) {
        this.groupBy = groupBy;
    }
    
    public List<QueryCondition> getHaving() {
        return having;
    }
    
    public void setHaving(List<QueryCondition> having) {
        this.having = having;
    }
    
    public List<JoinConfig> getJoins() {
        return joins;
    }
    
    public void setJoins(List<JoinConfig> joins) {
        this.joins = joins;
    }
    
    public Map<String, Object> getParameters() {
        return parameters;
    }
    
    public void setParameters(Map<String, Object> parameters) {
        this.parameters = parameters;
    }
    
    public boolean isDistinct() {
        return distinct;
    }
    
    public void setDistinct(boolean distinct) {
        this.distinct = distinct;
    }
    
    public Integer getLimit() {
        return limit;
    }
    
    public void setLimit(Integer limit) {
        this.limit = limit;
    }
    
    public Integer getOffset() {
        return offset;
    }
    
    public void setOffset(Integer offset) {
        this.offset = offset;
    }
    
    public String getQueryHint() {
        return queryHint;
    }
    
    public void setQueryHint(String queryHint) {
        this.queryHint = queryHint;
    }
    
    public boolean isIncludeDeleted() {
        return includeDeleted;
    }
    
    public void setIncludeDeleted(boolean includeDeleted) {
        this.includeDeleted = includeDeleted;
    }
    
    public String getTenantId() {
        return tenantId;
    }
    
    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }
    
    public List<AggregateField> getAggregateFields() {
        return aggregateFields;
    }
    
    public void setAggregateFields(List<AggregateField> aggregateFields) {
        this.aggregateFields = aggregateFields;
    }
    
    /**
     * 查询条件
     */
    public static class QueryCondition {
        private String field;
        private QueryOperator operator;
        private Object value;
        private LogicalOperator logicalOperator;
        
        public QueryCondition() {
            this.logicalOperator = LogicalOperator.AND;
        }
        
        public QueryCondition(String field, QueryOperator operator, Object value) {
            this();
            this.field = field;
            this.operator = operator;
            this.value = value;
        }
        
        public QueryCondition(String field, QueryOperator operator, Object value, LogicalOperator logicalOperator) {
            this.field = field;
            this.operator = operator;
            this.value = value;
            this.logicalOperator = logicalOperator;
        }
        
        // Getters and Setters
        public String getField() {
            return field;
        }
        
        public void setField(String field) {
            this.field = field;
        }
        
        public QueryOperator getOperator() {
            return operator;
        }
        
        public void setOperator(QueryOperator operator) {
            this.operator = operator;
        }
        
        public Object getValue() {
            return value;
        }
        
        public void setValue(Object value) {
            this.value = value;
        }
        
        public LogicalOperator getLogicalOperator() {
            return logicalOperator;
        }
        
        public void setLogicalOperator(LogicalOperator logicalOperator) {
            this.logicalOperator = logicalOperator;
        }
    }
    
    /**
     * 排序条件
     */
    public static class OrderCondition {
        private String field;
        private OrderDirection direction;
        
        public OrderCondition() {}
        
        public OrderCondition(String field, OrderDirection direction) {
            this.field = field;
            this.direction = direction;
        }
        
        // Getters and Setters
        public String getField() {
            return field;
        }
        
        public void setField(String field) {
            this.field = field;
        }
        
        public OrderDirection getDirection() {
            return direction;
        }
        
        public void setDirection(OrderDirection direction) {
            this.direction = direction;
        }
    }
    
    /**
     * 关联查询配置
     */
    public static class JoinConfig {
        private String targetEntity;
        private String joinField;
        private String targetField;
        private JoinType joinType;
        private List<QueryCondition> conditions;
        
        public JoinConfig() {
            this.conditions = new ArrayList<>();
        }
        
        // Getters and Setters
        public String getTargetEntity() {
            return targetEntity;
        }
        
        public void setTargetEntity(String targetEntity) {
            this.targetEntity = targetEntity;
        }
        
        public String getJoinField() {
            return joinField;
        }
        
        public void setJoinField(String joinField) {
            this.joinField = joinField;
        }
        
        public String getTargetField() {
            return targetField;
        }
        
        public void setTargetField(String targetField) {
            this.targetField = targetField;
        }
        
        public JoinType getJoinType() {
            return joinType;
        }
        
        public void setJoinType(JoinType joinType) {
            this.joinType = joinType;
        }
        
        public List<QueryCondition> getConditions() {
            return conditions;
        }
        
        public void setConditions(List<QueryCondition> conditions) {
            this.conditions = conditions;
        }
    }
    
    /**
     * 查询操作符枚举
     */
    public enum QueryOperator {
        EQ, NE, GT, GE, LT, LE, LIKE, IN, NOT_IN, IS_NULL, IS_NOT_NULL, BETWEEN
    }
    
    /**
     * 逻辑操作符枚举
     */
    public enum LogicalOperator {
        AND, OR
    }
    
    /**
     * 排序方向枚举
     */
    public enum OrderDirection {
        ASC, DESC
    }
    
    /**
     * 关联类型枚举
     */
    public enum JoinType {
        INNER, LEFT, RIGHT, FULL
    }
    
    /**
     * 聚合字段
     */
    public static class AggregateField {
        private String field;
        private AggregateFunction function;
        private String alias;
        
        public AggregateField() {}
        
        public AggregateField(String field, AggregateFunction function) {
            this.field = field;
            this.function = function;
        }
        
        public AggregateField(String field, AggregateFunction function, String alias) {
            this.field = field;
            this.function = function;
            this.alias = alias;
        }
        
        // Getters and Setters
        public String getField() {
            return field;
        }
        
        public void setField(String field) {
            this.field = field;
        }
        
        public AggregateFunction getFunction() {
            return function;
        }
        
        public void setFunction(AggregateFunction function) {
            this.function = function;
        }
        
        public String getAlias() {
            return alias;
        }
        
        public void setAlias(String alias) {
            this.alias = alias;
        }
    }
    
    /**
     * 聚合函数枚举
     */
    public enum AggregateFunction {
        COUNT, SUM, AVG, MAX, MIN, COUNT_DISTINCT
    }
}