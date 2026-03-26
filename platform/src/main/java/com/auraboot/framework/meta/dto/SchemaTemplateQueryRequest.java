package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * Schema模板查询请求
 * 
 * @author AuraBoot
 * @since 2024-01-01
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SchemaTemplateQueryRequest {
    
    /**
     * 租户ID
     */
    private String tenantId;
    
    /**
     * 模板名称（模糊查询）
     */
    private String templateName;
    
    /**
     * 模板代码（精确查询）
     */
    private String templateCode;
    
    /**
     * 模板类型
     */
    private String templateType;
    
    /**
     * 状态
     */
    private String status;
    
    /**
     * 是否公开
     */
    private Boolean isPublic;
    
    /**
     * 标签（包含查询）
     */
    private String[] tags;
    
    /**
     * 创建人
     */
    private String createdBy;
    
    /**
     * 创建时间开始
     */
    private LocalDateTime createdAtStart;
    
    /**
     * 创建时间结束
     */
    private LocalDateTime createdAtEnd;
    
    /**
     * 更新时间开始
     */
    private LocalDateTime updatedAtStart;
    
    /**
     * 更新时间结束
     */
    private LocalDateTime updatedAtEnd;
    
    /**
     * 关键词搜索（在名称、描述、标签中搜索）
     */
    private String keyword;
    
    /**
     * 排序字段
     */
    private String sortBy = "createdAt";
    
    /**
     * 排序方向
     */
    private String sortDirection = "desc";
    
    /**
     * 检查是否有时间范围查询条件
     */
    public boolean hasTimeRangeFilter() {
        return createdAtStart != null || createdAtEnd != null ||
               updatedAtStart != null || updatedAtEnd != null;
    }
    
    /**
     * 检查是否有标签过滤条件
     */
    public boolean hasTagsFilter() {
        return tags != null && tags.length > 0;
    }
}