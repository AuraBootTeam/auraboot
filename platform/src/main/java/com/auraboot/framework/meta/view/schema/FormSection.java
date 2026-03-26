package com.auraboot.framework.meta.view.schema;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 表单区域Bean
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FormSection {
    

    /**
     * 区域代码,可选
     */
    private String code;
    
    /**
     * 国际化标题,可选
     */
    private Map<String, String> title;
    
    /**
     * 布局配置
     */
    private Map<String, Object> layout;
    
    /**
     * 字段列表
     */
    private List<FormField> fields;
}