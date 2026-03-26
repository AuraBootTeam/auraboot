package com.auraboot.framework.meta.view.schema.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.Map;

/**
 * 表单动作Bean
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Action extends CommonConfig {
    
    /**
     * 动作代码
     */
    private String code;
    
    /**
     * 类型
     */
    private String type;

    /**
     * 权限配置
     */
    private String permission;
    
    /**
     * 行为配置
     */
    private Event event;
}