package com.auraboot.framework.meta.entity.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * UI元数据Bean
 * 用于DictEntity的uiMeta字段
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UiPayload {
    
    /**
     * 显示名称
     */
    private String displayName;
    
    /**
     * 图标
     */
    private String icon;
    
    /**
     * 颜色主题
     */
    private String color;
    
    /**
     * 布局配置
     */
    private LayoutConfig layout;
    
    /**
     * 列表视图配置
     */
    private ListView listView;
    
    /**
     * 表单视图配置
     */
    private FormView formView;
    
    /**
     * 详情视图配置
     */
    private DetailView detailView;
    
    /**
     * 权限配置
     */
    private AccessControl permissions;
    
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
        private String type; // grid, flex, table
        private Integer columns;
        private String gap;
        private String padding;
        private String margin;
    }
    
    /**
     * 列表视图配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ListView {
        private List<String> defaultColumns;
        private List<String> sortableColumns;
        private List<String> searchableColumns;
        private Integer pageSize;
        private Boolean enablePagination;
        private Boolean enableSearch;
        private Boolean enableFilter;
        private Boolean enableExport;
    }
    
    /**
     * 表单视图配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FormView {
        private List<String> fieldOrder;
        private List<String> requiredFields;
        private List<String> readonlyFields;
        private List<String> hiddenFields;
        private Map<String, Object> validation;
        private Boolean enableAutoSave;
        private Integer autoSaveInterval;
    }
    
    /**
     * 详情视图配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DetailView {
        private List<String> fieldOrder;
        private List<String> hiddenFields;
        private Boolean enableEdit;
        private Boolean enableDelete;
        private Boolean enableClone;
    }
    
    /**
     * 权限配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AccessControl {
        private Boolean canCreate;
        private Boolean canRead;
        private Boolean canUpdate;
        private Boolean canDelete;
        private List<String> roles;
        private List<String> permissions;
    }
}