package com.auraboot.framework.meta.view.schema.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.Map;

/**
 * 表单验证规则Bean
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ValidationRule {
    
    /**
     * 验证类型
     */
    private String type;
    
    /**
     * 验证值
     */
    private Object value;
    
    /**
     * 国际化错误消息
     */
    private Map<String, String> message;
}