package com.auraboot.framework.meta.view.schema;

import com.auraboot.framework.meta.view.schema.common.Meta;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 查询预设Bean
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QueryPreset {
    
    /**
     * 元数据
     */
    private Meta meta;
    
    /**
     * 过滤器配置
     */
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    private Map<String, Object> filters = new HashMap<>();
    
    /**
     * 排序配置
     */
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    private Map<String, Object> sorts = new HashMap<>();
    
    /**
     * 分页配置
     */
    private Map<String, Object> pagination;
    
    /**
     * 缓存配置
     */
    private Map<String, Object> cache;
    
    /**
     * 选择字段配置
     */
    private Map<String, Object> selects;
    
    /**
     * 聚合配置
     */
    private Map<String, Object> aggregations;
    
    /**
     * 优化配置
     */
    private Map<String, Object> optimization;
    
    /**
     * 安全配置
     */
    private Map<String, Object> security;
    
    /**
     * 审计配置
     */
    private Map<String, Object> audit;
    
    /**
     * 权限策略配置
     */
    private Map<String, Object> policy;
}