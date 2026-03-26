package com.auraboot.framework.meta.bean;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.Map;

/**
 * 查询排序Bean
 * 用于QueryPreset的sorts字段
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QuerySortBean {
    
    /**
     * 字段名
     */
    private String field;
    
    /**
     * 排序方向 (asc, desc)
     */
    private String direction;
    
    /**
     * 排序优先级
     */
    private Integer priority;
    
    /**
     * 空值处理 (nulls_first, nulls_last)
     */
    private String nulls;
    
    /**
     * 扩展属性
     */
    private Map<String, Object> extra;
}