package com.auraboot.framework.meta.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.auraboot.framework.application.typehandler.JsonNodeTypeHandler;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * 查询字段实体类
 * 对应表：ab_named_query_field
 * 
 * 该实体用于定义命名查询的字段白名单和操作符限制
 * 确保查询的安全性和可控性
 */
@Data
@TableName(value = "ab_named_query_field", autoResultMap = true)
public class NamedQueryField {

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 租户ID
     */
    @TableField("tenant_id")
    private Long tenantId;

      

    

    /**
     * 查询编码
     * 关联到ab_named_query表的code字段
     */
    @TableField("query_code")
    private String queryCode;

    /**
     * 字段编码
     * 用于标识查询中的字段
     */
    @TableField("field_code")
    private String fieldCode;

    /**
     * 列表达式
     * 实际的SQL列表达式或列名
     */
    @TableField("column_expr")
    private String columnExpr;

    /**
     * 数据类型
     * STRING, NUMBER, DATE, BOOLEAN等
     */
    @TableField("data_type")
    private String dataType;

    /**
     * Allowed operators whitelist
     * Stored as JSONB array
     */
    @TableField(value = "operators", typeHandler = com.auraboot.framework.application.database.mybatis.JsonbStringArrayTypeHandler.class)
    private String[] operators;

    /**
     * 关联的字典编码
     * 用于字段值的翻译和验证
     */
    @TableField("dict_code")
    private String dictCode;

    /**
     * 是否可排序
     */
    @TableField("sortable")
    private Boolean sortable;

    /**
     * 是否可搜索
     */
    @TableField("searchable")
    private Boolean searchable;

    /**
     * UI component type: text, number, numberRange, select, dateRange, date, userPicker, cascader, search, switch
     */
    @TableField("ui_component")
    private String uiComponent;

    @TableField("placeholder")
    private String placeholder;

    @TableField("default_value")
    private String defaultValue;

    /**
     * Linked field code for cascading (e.g., city depends on province)
     */
    @TableField("linked_field")
    private String linkedField;

    @TableField("required")
    private Boolean required;

    @TableField("display_name")
    private String displayName;

    @TableField("sort_order")
    private Integer sortOrder;

    @TableField("field_group")
    private String fieldGroup;

    /**
     * Extra UI configuration (JSON) — e.g., min/max for number, options for select
     */
    @TableField(value = "ui_config", typeHandler = JsonNodeTypeHandler.class, jdbcType = org.apache.ibatis.type.JdbcType.OTHER)
    private JsonNode uiConfig;

    /**
     * Data source: PLUGIN (created by plugin import) or USER (created by user)
     */
    @TableField("source")
    private String source;

    /**
     * 创建时间
     */
    @TableField("created_at")
    private Instant createdAt;

    /**
     * 更新时间
     */
    @TableField("updated_at")
    private Instant updatedAt;

    /**
     * 构造函数
     */
    public NamedQueryField() {
        this.sortable = false;
        this.searchable = true;
        this.source = "user";

        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /**
     * 构造函数
     * @param tenantId 租户ID
     * @param queryCode 查询编码
     * @param fieldCode 字段编码
     * @param columnExpr 列表达式
     * @param dataType 数据类型
     */
    public NamedQueryField(Long tenantId,String queryCode, String fieldCode, String columnExpr, String dataType) {
        this();
        this.tenantId = tenantId;
          
        this.queryCode = queryCode;
        this.fieldCode = fieldCode;
        this.columnExpr = columnExpr;
        this.dataType = dataType;
    }

    /**
     * Check if sortable (for external use)
     * @return whether sortable
     */
    public boolean checkSortable() {
        return Boolean.TRUE.equals(sortable);
    }

    /**
     * Check if searchable (for external use)
     * @return whether searchable
     */
    public boolean checkSearchable() {
        return Boolean.TRUE.equals(searchable);
    }

    /**
     * 检查是否有关联字典
     * @return 是否有关联字典
     */
    public boolean hasDict() {
        return dictCode != null && !dictCode.trim().isEmpty();
    }

    /**
     * 检查是否有操作符限制
     * @return 是否有操作符限制
     */
    public boolean hasOperators() {
        return operators != null && operators.length > 0;
    }

    /**
     * 获取操作符列表
     * @return 操作符列表
     */
    public List<String> getOperatorList() {
        return hasOperators() ? Arrays.asList(operators) : Arrays.asList();
    }

    /**
     * 检查是否支持指定操作符
     * @param operator 操作符
     * @return 是否支持
     */
    public boolean supportsOperator(String operator) {
        if (!hasOperators()) {
            return false;
        }
        return Arrays.asList(operators).contains(operator);
    }

    /**
     * 设置操作符列表
     * @param operatorList 操作符列表
     */
    public void setOperatorList(List<String> operatorList) {
        if (operatorList != null && !operatorList.isEmpty()) {
            this.operators = operatorList.toArray(new String[0]);
        } else {
            this.operators = null;
        }
    }

    /**
     * 添加操作符
     * @param operator 操作符
     */
    public void addOperator(String operator) {
        if (operator == null || operator.trim().isEmpty()) {
            return;
        }
        
        List<String> currentOperators = getOperatorList();
        if (!currentOperators.contains(operator)) {
            currentOperators.add(operator);
            setOperatorList(currentOperators);
        }
    }

    /**
     * 移除操作符
     * @param operator 操作符
     */
    public void removeOperator(String operator) {
        if (operator == null || !hasOperators()) {
            return;
        }
        
        List<String> currentOperators = getOperatorList();
        currentOperators.remove(operator);
        setOperatorList(currentOperators);
    }

    /**
     * 检查字段定义是否有效
     * @return 是否有效
     */
    public boolean isValid() {
        return tenantId != null 
            && queryCode != null && !queryCode.trim().isEmpty()
            && fieldCode != null && !fieldCode.trim().isEmpty()
            && columnExpr != null && !columnExpr.trim().isEmpty()
            && dataType != null && !dataType.trim().isEmpty();
    }

    /**
     * 获取字段的完整标识
     * @return 完整标识
     */
    public String getFullFieldCode() {
        StringBuilder sb = new StringBuilder();

        sb.append(queryCode).append(".").append(fieldCode);
        return sb.toString();
    }

    /**
     * 根据数据类型获取默认操作符
     * @return 默认操作符列表
     */
    public List<String> getDefaultOperators() {
        if (dataType == null) {
            return Arrays.asList("eq", "ne");
        }
        
        switch (dataType.toUpperCase()) {
            case "string":
                return Arrays.asList("eq", "ne", "like", "ilike", "starts_with", "ends_with", "contains");
            case "number":
                return Arrays.asList("eq", "ne", "gt", "gte", "lt", "lte", "between");
            case "date":
                return Arrays.asList("eq", "ne", "gt", "gte", "lt", "lte", "between");
            case "boolean":
                return Arrays.asList("eq", "ne");
            default:
                return Arrays.asList("eq", "ne", "in", "not_in");
        }
    }

    /**
     * Get field summary
     * @return field summary
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Field: ").append(fieldCode);
        sb.append(", Type: ").append(dataType);
        sb.append(", Column: ").append(columnExpr);
        if (checkSortable()) {
            sb.append(", Sortable");
        }
        if (checkSearchable()) {
            sb.append(", Searchable");
        }
        if (hasDict()) {
            sb.append(", Dict: ").append(dictCode);
        }
        if (hasOperators()) {
            sb.append(", Operators: ").append(Arrays.toString(operators));
        }
        return sb.toString();
    }
}