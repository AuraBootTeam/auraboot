package com.auraboot.framework.meta.entity.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 区块定义配置Bean
 * 用于BlockEntity的definition字段
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BlockDefinitionBean {
    
    /**
     * 区块类型
     */
    private String blockType;
    
    /**
     * 区块标题
     */
    private String title;
    
    /**
     * 区块描述
     */
    private String description;
    
    /**
     * 布局配置
     */
    private LayoutConfig layout;
    
    /**
     * 样式配置
     */
    private StyleConfig style;
    
    /**
     * 数据配置
     */
    private DataConfig data;
    
    /**
     * 交互配置
     */
    private InteractionConfig interaction;
    
    /**
     * 权限配置
     */
    private AccessControl permission;
    
    /**
     * 子区块列表
     */
    private List<ChildBlock> children;
    
    /**
     * 条件配置
     */
    private ConditionalConfig conditional;
    
    /**
     * 扩展属性
     */
    private Map<String, Object> extensions;
    
    /**
     * 布局配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LayoutConfig {
        private String layoutType; // grid, flex, absolute, flow
        private Integer columns;
        private Integer rows;
        private String direction; // row, column
        private String justifyContent;
        private String alignItems;
        private String gap;
        private String padding;
        private String margin;
        private Boolean responsive;
        private Map<String, ResponsiveConfig> breakpoints;
        
        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class ResponsiveConfig {
            private Integer columns;
            private String direction;
            private String gap;
            private String padding;
            private Boolean hidden;
        }
    }
    
    /**
     * 样式配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StyleConfig {
        private String backgroundColor;
        private String textColor;
        private String borderColor;
        private String borderWidth;
        private String borderRadius;
        private String boxShadow;
        private String fontSize;
        private String fontWeight;
        private String fontFamily;
        private String textAlign;
        private Integer zIndex;
        private String opacity;
        private String transform;
        private String transition;
        private Map<String, String> customStyles;
        private List<String> cssClasses;
    }
    
    /**
     * 数据配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DataConfig {
        private String dataSource;
        private String dataPath;
        private Map<String, Object> staticData;
        private List<DataBinding> bindings;
        private FilterConfig filter;
        private SortConfig sort;
        private PaginationConfig pagination;
        private Boolean autoRefresh;
        private Integer refreshInterval;
        
        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class DataBinding {
            private String field;
            private String expression;
            private String formatter;
            private Object defaultValue;
        }
        
        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class FilterConfig {
            private List<FilterRule> rules;
            private String logic; // and, or
            
            @Data
            @JsonIgnoreProperties(ignoreUnknown = true)
            @JsonInclude(JsonInclude.Include.NON_NULL)
            public static class FilterRule {
                private String field;
                private String operator;
                private Object value;
            }
        }
        
        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class SortConfig {
            private String field;
            private String direction; // asc, desc
        }
        
        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class PaginationConfig {
            private Integer pageSize;
            private Boolean showPagination;
            private String paginationType; // simple, full
        }
    }
    
    /**
     * 交互配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class InteractionConfig {
        private Boolean clickable;
        private Boolean draggable;
        private Boolean resizable;
        private Boolean selectable;
        private List<EventHandler> events;
        private Map<String, ActionConfig> actions;
        
        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class EventHandler {
            private String event; // click, hover, focus, etc.
            private String action;
            private Map<String, Object> params;
            private String condition;
        }
        
        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class ActionConfig {
            private String type; // navigate, api, script, etc.
            private String target;
            private Map<String, Object> params;
            private String confirmation;
        }
    }
    
    /**
     * 权限配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AccessControl {
        private List<String> roles;
        private List<String> permissions;
        private String expression;
        private Boolean inheritFromParent;
    }
    
    /**
     * 子区块
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ChildBlock {
        private String id;
        private String type;
        private String name;
        private Map<String, Object> props;
        private LayoutConfig layout;
        private StyleConfig style;
        private List<ChildBlock> children;
    }
    
    /**
     * 条件配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ConditionalConfig {
        private String showCondition;
        private String hideCondition;
        private String enableCondition;
        private String disableCondition;
        private Map<String, String> dynamicProps;
    }
}