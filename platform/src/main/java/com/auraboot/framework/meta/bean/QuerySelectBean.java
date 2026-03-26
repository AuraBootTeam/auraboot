package com.auraboot.framework.meta.bean;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.Map;

/**
 * 查询选择字段Bean
 * 用于QueryPreset的selects字段
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QuerySelectBean {
    
    /**
     * 字段名
     */
    private String field;
    
    /**
     * 别名
     */
    private String alias;
    
    /**
     * 聚合函数 (sum, count, avg, max, min)
     */
    private String aggregate;
    
    /**
     * 是否去重
     */
    private Boolean distinct;
    
    /**
     * 表达式
     */
    private String expression;
    
    /**
     * 扩展属性
     */
    private Map<String, Object> extra;
}