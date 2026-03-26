package com.auraboot.framework.meta.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;
import java.util.Locale;

/**
 * 查询条件
 *
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryCondition {
    
    /**
     * 字段名
     */
    private String fieldName;
    
    /**
     * 操作符
     */
    private Operator operator;
    
    /**
     * 值
     */
    private Object value;
    
    /**
     * 值列表（用于IN、NOT_IN等操作）
     */
    private List<Object> values;
    
    /**
     * 逻辑连接符
     */
    @Builder.Default
    private LogicalOperator logicalOperator = LogicalOperator.AND;
    
    /**
     * 子条件（用于复杂查询）
     */
    private List<QueryCondition> subConditions;
    
    public enum Operator {
        EQ,         // 等于
        NE,         // 不等于
        GT,         // 大于
        GE,         // 大于等于
        LT,         // 小于
        LE,         // 小于等于
        LIKE,       // 模糊匹配
        NOT_LIKE,   // 不匹配
        IN,         // 在列表中
        NOT_IN,     // 不在列表中
        IS_NULL,    // 为空
        IS_NOT_NULL,// 不为空
        BETWEEN,    // 在范围内
        NOT_BETWEEN; // 不在范围内

        @JsonCreator
        public static Operator fromCode(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            String normalized = value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
            return switch (normalized) {
                case "NEQ" -> NE;
                case "GTE" -> GE;
                case "LTE" -> LE;
                default -> Operator.valueOf(normalized);
            };
        }

        @JsonValue
        public String toCode() {
            return name();
        }
    }
    
    public enum LogicalOperator {
        AND, OR
    }
}
