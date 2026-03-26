package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 页面元数据响应DTO
 * 
 * @author AuraBoot Framework
 * @since 1.0.0
 */
@Data
public class PageMetadataResponse {
    
    /**
     * 页面ID
     */
    private String pageId;
    
    /**
     * 页面名称
     */
    private String name;
    
    /**
     * 页面标题
     */
    private String title;
    
    /**
     * 页面描述
     */
    private String description;
    
    /**
     * 数据源配置
     */
    private String dataSource;
    
    /**
     * 表单Schema
     */
    private Map<String, Object> formSchema;
    
    /**
     * 列表Schema
     */
    private Map<String, Object> listSchema;
    
    /**
     * 可搜索字段列表
     */
    private List<String> searchableFields;
    
    /**
     * 可排序字段列表
     */
    private List<String> sortableFields;
    
    /**
     * 字段选项配置
     */
    private Map<String, List<FieldOption>> fieldOptions;
    
    /**
     * 权限配置
     */
    private Map<String, Boolean> permissions;
    
    /**
     * 扩展配置
     */
    private Map<String, Object> config;
    
    /**
     * 字段选项
     */
    @Data
    public static class FieldOption {
        /**
         * 选项值
         */
        private String value;
        
        /**
         * 选项标签
         */
        private String label;
        
        /**
         * 选项描述
         */
        private String description;
        
        /**
         * 是否启用
         */
        private Boolean enabled = true;
        
        /**
         * 扩展属性
         */
        private Map<String, Object> extra;
    }
}