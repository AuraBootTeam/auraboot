package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 动态关联数据响应DTO
 * 
 * @author AuraBoot Framework
 * @since 1.0.0
 */
@Data
public class DynamicRelationResponse {
    
    /**
     * 子记录列表
     */
    private List<Map<String, Object>> children;
    
    /**
     * 父记录列表
     */
    private List<Map<String, Object>> parents;
    
    /**
     * 兄弟记录列表
     */
    private List<Map<String, Object>> siblings;
    
    /**
     * 关联关系配置
     */
    private List<RelationConfig> relations;
    
    /**
     * 扩展数据
     */
    private Map<String, Object> metadata;
    
    /**
     * 关联关系配置
     */
    @Data
    public static class RelationConfig {
        /**
         * 关联类型（parent, child, sibling）
         */
        private String type;
        
        /**
         * 关联表名
         */
        private String tableName;
        
        /**
         * 本表关联字段
         */
        private String localField;
        
        /**
         * 外表关联字段
         */
        private String foreignField;
        
        /**
         * 关联名称
         */
        private String name;
        
        /**
         * 关联描述
         */
        private String description;
        
        /**
         * 是否启用
         */
        private Boolean enabled = true;
    }
}