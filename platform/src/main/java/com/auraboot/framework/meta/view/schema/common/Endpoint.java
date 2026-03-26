package com.auraboot.framework.meta.view.schema.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.Map;

/**
 * 表单端点Bean
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Endpoint {
    
    /**
     * 端点URL
     */
    private String url;
    
    /**
     * 请求方法
     */
    private String method;
    
    /**
     * 权限
     */
    private String permission;
    
    /**
     * 数据转换配置
     */
    private Map<String, Object> transform;

}