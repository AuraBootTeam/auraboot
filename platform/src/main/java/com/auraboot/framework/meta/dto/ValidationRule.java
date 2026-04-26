package com.auraboot.framework.meta.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.Map;

/**
 * 验证规则
 *
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidationRule {
    
    /**
     * 规则名称
     */
    private String name;
    
    /**
     * 规则类型
     */
    private RuleType type;
    
    /**
     * 规则表达式或值
     */
    private String expression;
    
    /**
     * 错误消息
     */
    private String errorMessage;
    
    /**
     * 规则参数
     */
    private Map<String, Object> parameters;
    
    /**
     * 是否启用
     */
    @Builder.Default
    private Boolean enabled = true;
    
    public enum RuleType {
        REQUIRED,       // 必填
        MIN_LENGTH,     // 最小长度
        MAX_LENGTH,     // 最大长度
        PATTERN,        // 正则表达式
        RANGE,          // 数值范围
        EMAIL,          // 邮箱格式
        PHONE,          // 手机号格式
        CUSTOM          // 自定义规则
    }
}