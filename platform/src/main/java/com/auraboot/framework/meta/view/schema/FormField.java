package com.auraboot.framework.meta.view.schema;

import com.auraboot.framework.meta.view.schema.common.CommonConfig;
import com.auraboot.framework.meta.view.schema.common.ValidationRule;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * 表单字段Bean
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FormField extends CommonConfig {
    
    /**
     * 字段代码
     */
    private String code;
    
    /**
     * 字段类型
     */
    private String type;

    /**
     * 组件类型
     */
    private String component;

    /**
     * 验证规则
     */
    private List<ValidationRule> validation;
}