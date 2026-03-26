package com.auraboot.framework.meta.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 查询操作符实体类
 * 对应表：ab_query_operator
 * 
 * 该实体用于定义查询中允许使用的操作符及其SQL模板
 * 确保查询操作的安全性和标准化
 */
@Data
@TableName(value = "ab_query_operator", autoResultMap = true)
public class QueryOperator {

    /**
     * 操作符编码（主键）
     * 如：eq, ne, gt, like等
     */
    @TableId("op_code")
    private String opCode;

    /**
     * SQL模板
     * 包含占位符的SQL表达式模板
     * 如：{column} = {value}
     */
    @TableField("sql_tpl")
    private String sqlTpl;

    /**
     * 值类型
     * any: 任意类型
     * string: 字符串类型
     * number: 数字类型
     * array: 数组类型
     * range: 范围类型
     * none: 无值类型
     */
    @TableField("value_type")
    private String valueType;

    /**
     * 操作符说明
     */
    @TableField("notes")
    private String notes;

    /**
     * 构造函数
     */
    public QueryOperator() {
    }

    /**
     * 构造函数
     * @param opCode 操作符编码
     * @param sqlTpl SQL模板
     * @param valueType 值类型
     * @param notes 说明
     */
    public QueryOperator(String opCode, String sqlTpl, String valueType, String notes) {
        this.opCode = opCode;
        this.sqlTpl = sqlTpl;
        this.valueType = valueType;
        this.notes = notes;
    }

    /**
     * 检查是否为比较操作符
     * @return 是否为比较操作符
     */
    public boolean isComparisonOperator() {
        if (opCode == null) {
            return false;
        }
        return opCode.matches("^(eq|ne|gt|gte|lt|lte)$");
    }

    /**
     * 检查是否为字符串操作符
     * @return 是否为字符串操作符
     */
    public boolean isStringOperator() {
        if (opCode == null) {
            return false;
        }
        return opCode.matches("^(like|ilike|starts_with|ends_with|contains)$");
    }

    /**
     * 检查是否为数组操作符
     * @return 是否为数组操作符
     */
    public boolean isArrayOperator() {
        if (opCode == null) {
            return false;
        }
        return opCode.matches("^(in|not_in)$");
    }

    /**
     * 检查是否为空值操作符
     * @return 是否为空值操作符
     */
    public boolean isNullOperator() {
        if (opCode == null) {
            return false;
        }
        return opCode.matches("^(is_null|is_not_null)$");
    }

    /**
     * 检查是否为范围操作符
     * @return 是否为范围操作符
     */
    public boolean isRangeOperator() {
        return "between".equals(opCode);
    }

    /**
     * 检查是否需要值参数
     * @return 是否需要值参数
     */
    public boolean requiresValue() {
        return !"none".equals(valueType) && !isNullOperator();
    }

    /**
     * 检查是否支持指定的数据类型
     * @param dataType 数据类型
     * @return 是否支持
     */
    public boolean supportsDataType(String dataType) {
        if ("any".equals(valueType)) {
            return true;
        }
        
        if (dataType == null) {
            return false;
        }
        
        switch (dataType.toUpperCase()) {
            case "string":
                return "string".equals(valueType) || "any".equals(valueType) || isStringOperator();
            case "number":
                return "number".equals(valueType) || "any".equals(valueType) || isComparisonOperator();
            case "date":
                return "number".equals(valueType) || "any".equals(valueType) || isComparisonOperator() || isRangeOperator();
            case "boolean":
                return "any".equals(valueType) || isComparisonOperator();
            default:
                return "any".equals(valueType);
        }
    }

    /**
     * 生成SQL表达式
     * @param column 列名
     * @param value 值
     * @return SQL表达式
     */
    public String generateSql(String column, Object value) {
        if (sqlTpl == null || column == null) {
            return null;
        }
        
        String sql = sqlTpl.replace("{column}", column);
        
        if (requiresValue() && value != null) {
            if (isRangeOperator() && value instanceof Object[]) {
                Object[] range = (Object[]) value;
                if (range.length >= 2) {
                    sql = sql.replace("{value1}", "?").replace("{value2}", "?");
                }
            } else {
                sql = sql.replace("{value}", "?");
            }
        }
        
        return sql;
    }

    /**
     * 验证值的有效性
     * @param value 值
     * @return 是否有效
     */
    public boolean validateValue(Object value) {
        if (!requiresValue()) {
            return true;
        }
        
        if (value == null) {
            return false;
        }
        
        switch (valueType) {
            case "string":
                return value instanceof String;
            case "number":
                return value instanceof Number || (value instanceof String && ((String) value).matches("-?\\d+(\\.\\d+)?"));
            case "array":
                return value instanceof Object[] || value instanceof java.util.Collection;
            case "range":
                return value instanceof Object[] && ((Object[]) value).length >= 2;
            case "none":
                return true;
            case "any":
            default:
                return true;
        }
    }

    /**
     * 获取操作符的显示名称
     * @return 显示名称
     */
    public String getDisplayName() {
        if (notes != null && !notes.trim().isEmpty()) {
            return notes;
        }
        return opCode;
    }

    /**
     * 获取操作符类别
     * @return 操作符类别
     */
    public String getCategory() {
        if (isComparisonOperator()) {
            return "比较操作符";
        } else if (isStringOperator()) {
            return "字符串操作符";
        } else if (isArrayOperator()) {
            return "数组操作符";
        } else if (isNullOperator()) {
            return "空值操作符";
        } else if (isRangeOperator()) {
            return "范围操作符";
        } else {
            return "其他操作符";
        }
    }

    /**
     * 获取操作符摘要信息
     * @return 操作符摘要
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("操作符: ").append(opCode);
        sb.append(", 类别: ").append(getCategory());
        sb.append(", 值类型: ").append(valueType);
        if (notes != null && !notes.trim().isEmpty()) {
            sb.append(", 说明: ").append(notes);
        }
        return sb.toString();
    }
}