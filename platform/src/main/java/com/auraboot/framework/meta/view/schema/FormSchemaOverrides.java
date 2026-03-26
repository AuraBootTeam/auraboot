package com.auraboot.framework.meta.view.schema;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.Map;

/**
 * 表单Schema覆盖配置Bean
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FormSchemaOverrides {
    
    /**
     * 按字段键覆盖
     */
    private Map<String, FormField> fieldsByKey;
    
    /**
     * 按字段路径覆盖
     */
    private Map<String, FormField> fieldsByPath;
    
    /**
     * 全局覆盖
     */
    private Map<String, Object> global;
}