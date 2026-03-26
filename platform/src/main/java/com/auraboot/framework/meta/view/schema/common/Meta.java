package com.auraboot.framework.meta.view.schema.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.Map;

/**
 * 表单Schema元数据Bean
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Meta {
    /**
     * DSL版本
     */
    private String dslVersion;
    
    /**
     * 表单代码
     */
    private String blockCode;
    
    /**
     * 版本号
     */
    private String version;

    /**
     * 实体代码
     */
    private String entityCode;
    
    /**
     * 类型
     */
    private String type;

    /**
     * 标题
     */
    private Map<String, String> title;
}