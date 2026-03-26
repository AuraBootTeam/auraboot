package com.auraboot.framework.meta.view.schema.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.Map;

/**
 * 动作行为配置Bean
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EventBehavior {
    
    /**
     * 行为类型
     */
    private String type;
    
    /**
     * 目标地址
     */
    private String target;

    /**
     * 载荷配置
     */
    private Map<String,Object> payload;

    /**
     * 载荷配置
     */
    private Map<String,Object> props;
}