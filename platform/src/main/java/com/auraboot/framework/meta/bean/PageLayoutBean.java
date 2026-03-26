package com.auraboot.framework.meta.bean;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 页面布局Bean
 * 用于PageDefinition的layout字段
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PageLayoutBean {
    
    /**
     * 布局类型 (grid, flex, tabs, accordion)
     */
    private String type;
    
    /**
     * 列数
     */
    private Integer columns;
    
    /**
     * 行间距
     */
    private String rowGap;
    
    /**
     * 列间距
     */
    private String columnGap;
    
    /**
     * 布局区域
     */
    private List<LayoutSection> sections;
    
    /**
     * 响应式配置
     */
    private ResponsiveConfig responsive;
    
    /**
     * 扩展属性
     */
    private Map<String, Object> extra;
    
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LayoutSection {
        /**
         * 区域标识
         */
        private String key;
        
        /**
         * 区域标题
         */
        private String title;
        
        /**
         * 跨列数
         */
        private Integer span;
        
        /**
         * 偏移量
         */
        private Integer offset;
        
        /**
         * 是否可折叠
         */
        private Boolean collapsible;
        
        /**
         * 默认是否展开
         */
        private Boolean defaultExpanded;
        
        /**
         * 子区域
         */
        private List<LayoutSection> children;
    }
    
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ResponsiveConfig {
        /**
         * 小屏幕配置
         */
        private Map<String, Object> sm;
        
        /**
         * 中等屏幕配置
         */
        private Map<String, Object> md;
        
        /**
         * 大屏幕配置
         */
        private Map<String, Object> lg;
        
        /**
         * 超大屏幕配置
         */
        private Map<String, Object> xl;
    }
}