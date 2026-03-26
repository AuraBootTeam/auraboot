package com.auraboot.framework.meta.view.schema;

import com.auraboot.framework.meta.view.schema.common.*;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * 表单Schema Bean
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FormFacade extends CommonConfig {

    /**
     * 继承配置
     */
    private String base;
    /**
     * 元数据
     */
    private Meta meta;

    
    /**
     * 表单区域列表
     */
    private List<FormSection> sections;
    
    /**
     * 动作配置
     */
    private List<Action> actions;
    
    /**
     * 效果配置
     */
    private List<Event> events;
    
    /**
     * 覆盖配置
     */
    private FormSchemaOverrides overrides;
    
    /**
     * 端点配置
     */
    private Endpoint endpoint;
    

}