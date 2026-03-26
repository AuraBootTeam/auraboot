package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Schema模板更新请求
 * 
 * @author AuraBoot
 * @since 2024-01-01
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SchemaTemplateUpdateRequest {
    
    /**
     * 模板名称
     */
    @Size(max = 100, message = "模板名称长度不能超过100个字符")
    private String templateName;
    
    /**
     * 模板描述
     */
    @Size(max = 500, message = "模板描述长度不能超过500个字符")
    private String description;
    
    /**
     * 模板类型
     */
    private String templateType;
    
    /**
     * 模板内容
     */
    private Map<String, Object> templateContent;
    
    /**
     * 模板标签
     */
    private String[] tags;
    
    /**
     * 是否公开
     */
    private Boolean isPublic;
    
    /**
     * 排序顺序
     */
    private Integer sortOrder;
    
    /**
     * 状态
     */
    private String status;
    
    /**
     * 更新人
     */
    private String updatedBy;
    
    /**
     * 检查是否有任何字段需要更新
     */
    public boolean hasUpdates() {
        return templateName != null || description != null || templateType != null ||
               templateContent != null || tags != null || isPublic != null ||
               sortOrder != null || status != null;
    }
}