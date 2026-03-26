package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Schema模板响应DTO
 * 用于返回Schema模板的详细信息
 * 
 * @author AuraBoot
 * @since 2024-01-01
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SchemaTemplateResponse {
    
    /**
     * 模板PID
     */
    private String templatePid;
    
    /**
     * 租户ID
     */
    private String tenantId;
    
    /**
     * 模板名称
     */
    private String templateName;
    
    /**
     * 模板代码
     */
    private String templateCode;
    
    /**
     * 模板描述
     */
    private String description;
    
    /**
     * 模板类型
     */
    private String templateType;
    
    /**
     * 模板分类
     */
    private String category;
    
    /**
     * 模板版本
     */
    private String version;
    
    /**
     * 模板状态
     */
    private String status;
    
    /**
     * 是否为系统模板
     */
    private Boolean isSystem;
    
    /**
     * 是否为默认模板
     */
    private Boolean isDefault;
    
    /**
     * 模板内容（JSON格式）
     */
    private String templateContent;
    
    /**
     * 模板配置
     */
    private Map<String, Object> templateConfig;
    
    /**
     * 支持的实体类型
     */
    private List<String> supportedEntityTypes;
    
    /**
     * 模板标签
     */
    private List<String> tags;
    
    /**
     * 预览图URL
     */
    private String previewUrl;
    
    /**
     * 使用次数
     */
    private Long usageCount;
    
    /**
     * 评分
     */
    private Double rating;
    
    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
    
    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
    
    /**
     * 创建人
     */
    private String createdBy;
    
    /**
     * 更新人
     */
    private String updatedBy;
    
    /**
     * 模板元数据
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TemplateMetadata {
        /**
         * 作者信息
         */
        private String author;
        
        /**
         * 许可证
         */
        private String license;
        
        /**
         * 最小版本要求
         */
        private String minVersion;
        
        /**
         * 依赖项
         */
        private List<String> dependencies;
        
        /**
         * 兼容性信息
         */
        private Map<String, String> compatibility;
    }
    
    /**
     * 模板统计信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TemplateStats {
        /**
         * 下载次数
         */
        private Long downloadCount;
        
        /**
         * 收藏次数
         */
        private Long favoriteCount;
        
        /**
         * 评论次数
         */
        private Long commentCount;
        
        /**
         * 最后使用时间
         */
        private LocalDateTime lastUsedAt;
    }
    
    /**
     * 检查模板是否可用
     */
    public boolean isAvailable() {
        return "active".equalsIgnoreCase(status) || "published".equalsIgnoreCase(status);
    }
    
    /**
     * 检查是否为高评分模板
     */
    public boolean isHighRated() {
        return rating != null && rating >= 4.0;
    }
    
    /**
     * 检查是否为热门模板
     */
    public boolean isPopular() {
        return usageCount != null && usageCount >= 100;
    }
}