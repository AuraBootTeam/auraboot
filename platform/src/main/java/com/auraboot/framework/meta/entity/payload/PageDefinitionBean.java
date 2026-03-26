package com.auraboot.framework.meta.entity.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 页面定义配置Bean
 * 用于PageDefinitionEntity的definition字段
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PageDefinitionBean {
    
    /**
     * 页面类型
     */
    private String pageType;
    
    /**
     * 页面标题
     */
    private String title;
    
    /**
     * 页面描述
     */
    private String description;
    
    /**
     * 页面图标
     */
    private String icon;
    
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
     * 路由配置
     */
    private RouteConfig route;
    
    /**
     * 权限配置
     */
    private AccessControl permission;
    
    /**
     * SEO配置
     */
    private SeoConfig seo;
    
    /**
     * 页面区块列表
     */
    private List<PageBlock> blocks;
    
    /**
     * 脚本配置
     */
    private ScriptConfig script;
    
    /**
     * 缓存配置
     */
    private CacheConfig cache;
    
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
        private String layoutType; // fixed, fluid, responsive
        private String template; // single-column, two-column, three-column, custom
        private HeaderConfig header;
        private FooterConfig footer;
        private SidebarConfig sidebar;
        private ContentConfig content;
        private Boolean fullScreen;
        private String backgroundColor;
        private String backgroundImage;
        
        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class HeaderConfig {
            private Boolean visible;
            private Integer height;
            private String backgroundColor;
            private Boolean fixed;
            private List<String> components;
        }
        
        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class FooterConfig {
            private Boolean visible;
            private Integer height;
            private String backgroundColor;
            private Boolean fixed;
            private List<String> components;
        }
        
        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class SidebarConfig {
            private Boolean visible;
            private Integer width;
            private String position; // left, right
            private Boolean collapsible;
            private Boolean collapsed;
            private String backgroundColor;
        }
        
        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class ContentConfig {
            private String padding;
            private String margin;
            private String maxWidth;
            private String minHeight;
            private Boolean scrollable;
        }
    }
    
    /**
     * 样式配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StyleConfig {
        private String theme; // light, dark, auto
        private String primaryColor;
        private String secondaryColor;
        private String fontFamily;
        private String fontSize;
        private List<String> cssFiles;
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
        private List<DataSource> dataSources;
        private Map<String, Object> initialData;
        private List<DataBinding> bindings;
        private Boolean autoLoad;
        private Integer refreshInterval;
        
        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class DataSource {
            private String id;
            private String type; // api, static, computed
            private String url;
            private String method;
            private Map<String, Object> params;
            private Map<String, String> headers;
            private String transformer;
        }
        
        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class DataBinding {
            private String source;
            private String target;
            private String transformer;
            private Object defaultValue;
        }
    }
    
    /**
     * 路由配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RouteConfig {
        private String path;
        private String name;
        private List<String> aliases;
        private Map<String, String> params;
        private Map<String, String> query;
        private String redirect;
        private List<RouteGuard> guards;
        
        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class RouteGuard {
            private String type; // beforeEnter, beforeLeave
            private String condition;
            private String action;
            private Map<String, Object> params;
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
        private String redirectUrl;
        private String errorMessage;
    }
    
    /**
     * SEO配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SeoConfig {
        private String title;
        private String description;
        private String keywords;
        private String author;
        private String canonical;
        private Map<String, String> metaTags;
        private List<String> structuredData;
    }
    
    /**
     * 页面区块
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PageBlock {
        private String id;
        private String type;
        private String name;
        private Integer order;
        private String container; // header, content, footer, sidebar
        private Map<String, Object> props;
        private Map<String, Object> style;
        private String condition;
        private List<PageBlock> children;
    }
    
    /**
     * 脚本配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ScriptConfig {
        private List<String> jsFiles;
        private String initScript;
        private String destroyScript;
        private Map<String, String> eventHandlers;
        private List<CustomFunction> functions;
        
        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class CustomFunction {
            private String name;
            private String code;
            private List<String> params;
            private String description;
        }
    }
    
    /**
     * 缓存配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CacheConfig {
        private Boolean enabled;
        private Integer ttl; // 生存时间(秒)
        private String strategy; // memory, redis, hybrid
        private List<String> cacheKeys;
        private String invalidationRule;
    }
}