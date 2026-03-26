package com.auraboot.framework.meta.bean;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 查询过滤器Bean
 * 用于QueryPreset的filters字段
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QueryFilterBean {
    
    /**
     * 字段名
     */
    private String field;
    
    /**
     * 操作符 (eq, ne, gt, lt, gte, lte, like, in, not_in, between, is_null, is_not_null)
     */
    private String operator;
    
    /**
     * 过滤值
     */
    private Object value;
    
    /**
     * 逻辑连接符 (and, or)
     */
    private String logic;
    
    /**
     * 嵌套条件组
     */
    private List<QueryFilterBean> children;
    
    /**
     * 扩展属性
     */
    private Map<String, Object> extra;
}