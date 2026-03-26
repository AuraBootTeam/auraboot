package com.auraboot.framework.meta.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Schema模板DTO
 * 
 * @author AuraBoot
 * @since 2024-01-01
 */
public class SchemaTemplateDTO {
    
    /**
     * 模板ID
     */
    private Long id;
    
    /**
     * 业务主键
     */
    private String pid;
    
    /**
     * 租户ID
     */
    private Long tenantId;
    
    /**
     * 模板名称
     */
    private String templateName;
    
    /**
     * 模板代码
     */
    private String templateCode;
    
    /**
     * 模板类型
     */
    private String templateType;
    
    /**
     * 模板分类
     */
    private String category;
    
    /**
     * 模板描述
     */
    private String description;
    
    /**
     * 模板内容（JSON格式）
     */
    private String templateContent;
    
    /**
     * 预览图片URL
     */
    private String previewImage;
    
    /**
     * 是否为系统模板
     */
    private Boolean isSystem;
    
    /**
     * 是否启用
     */
    private Boolean enabled;
    
    /**
     * 版本号
     */
    private String version;
    
    /**
     * 标签列表
     */
    private List<String> tags;
    
    /**
     * 使用次数
     */
    private Integer usageCount;
    
    /**
     * 评分
     */
    private Double rating;
    
    /**
     * 扩展属性
     */
    private Map<String, Object> extensions;
    
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
    
    // getters and setters
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }
    
    public String getPid() {
        return pid;
    }
    
    public void setPid(String pid) {
        this.pid = pid;
    }
    
    public Long getTenantId() {
        return tenantId;
    }
    
    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }
    
    public String getTemplateName() {
        return templateName;
    }
    
    public void setTemplateName(String templateName) {
        this.templateName = templateName;
    }
    
    public String getTemplateCode() {
        return templateCode;
    }
    
    public void setTemplateCode(String templateCode) {
        this.templateCode = templateCode;
    }
    
    public String getTemplateType() {
        return templateType;
    }
    
    public void setTemplateType(String templateType) {
        this.templateType = templateType;
    }
    
    public String getCategory() {
        return category;
    }
    
    public void setCategory(String category) {
        this.category = category;
    }
    
    public String getDescription() {
        return description;
    }
    
    public void setDescription(String description) {
        this.description = description;
    }
    
    public String getTemplateContent() {
        return templateContent;
    }
    
    public void setTemplateContent(String templateContent) {
        this.templateContent = templateContent;
    }
    
    public String getPreviewImage() {
        return previewImage;
    }
    
    public void setPreviewImage(String previewImage) {
        this.previewImage = previewImage;
    }
    
    public Boolean getIsSystem() {
        return isSystem;
    }
    
    public void setIsSystem(Boolean isSystem) {
        this.isSystem = isSystem;
    }
    
    public Boolean getEnabled() {
        return enabled;
    }
    
    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }
    
    public String getVersion() {
        return version;
    }
    
    public void setVersion(String version) {
        this.version = version;
    }
    
    public List<String> getTags() {
        return tags;
    }
    
    public void setTags(List<String> tags) {
        this.tags = tags;
    }
    
    public Integer getUsageCount() {
        return usageCount;
    }
    
    public void setUsageCount(Integer usageCount) {
        this.usageCount = usageCount;
    }
    
    public Double getRating() {
        return rating;
    }
    
    public void setRating(Double rating) {
        this.rating = rating;
    }
    
    public Map<String, Object> getExtensions() {
        return extensions;
    }
    
    public void setExtensions(Map<String, Object> extensions) {
        this.extensions = extensions;
    }
    
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
    
    public String getCreatedBy() {
        return createdBy;
    }
    
    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }
    
    public String getUpdatedBy() {
        return updatedBy;
    }
    
    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }
}