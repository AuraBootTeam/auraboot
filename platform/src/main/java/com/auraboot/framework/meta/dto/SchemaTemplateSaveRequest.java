package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Schema模板保存请求
 * 
 * @author AuraBoot
 * @since 2024-01-01
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SchemaTemplateSaveRequest {
    
    /**
     * 租户ID
     */
    @NotBlank(message = "租户ID不能为空")
    private String tenantId;
    
    /**
     * 模板名称
     */
    @NotBlank(message = "模板名称不能为空")
    @Size(max = 100, message = "模板名称长度不能超过100个字符")
    private String templateName;
    
    /**
     * 模板代码
     */
    @NotBlank(message = "模板代码不能为空")
    @Size(max = 50, message = "模板代码长度不能超过50个字符")
    private String templateCode;
    
    /**
     * 模板描述
     */
    @Size(max = 500, message = "模板描述长度不能超过500个字符")
    private String description;
    
    /**
     * 模板类型
     */
    @NotBlank(message = "模板类型不能为空")
    private String templateType;
    
    /**
     * 模板内容
     */
    @NotNull(message = "模板内容不能为空")
    private Map<String, Object> templateContent;
    
    /**
     * 模板标签
     */
    private String[] tags;
    
    /**
     * 是否公开
     */
    private Boolean isPublic = false;
    
    /**
     * 排序顺序
     */
    private Integer sortOrder = 0;
    
    /**
     * 创建人
     */
    private String createdBy;
}