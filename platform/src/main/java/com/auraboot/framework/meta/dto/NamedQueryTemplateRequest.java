package com.auraboot.framework.meta.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 命名查询模板请求DTO
 * 
 * @author AuraBoot
 * @since 2024-12-24
 */
@Data
public class NamedQueryTemplateRequest {

    /**
     * 模板名称
     */
    @NotBlank(message = "模板名称不能为空")
    @Size(max = 200, message = "模板名称长度不能超过200个字符")
    private String templateName;

    /**
     * 模板描述
     */
    @Size(max = 1000, message = "模板描述长度不能超过1000个字符")
    private String templateDescription;

    /**
     * 模板类型
     */
    @NotBlank(message = "模板类型不能为空")
    private String templateType;

    /**
     * 模板内容
     */
    @NotBlank(message = "模板内容不能为空")
    private String templateContent;

    /**
     * 模板参数
     */
    private JsonNode templateParams;

    /**
     * 模板标签
     */
    private List<String> tags;

    /**
     * 是否公共模板
     */
    private Boolean isPublic = false;

    /**
     * 模板版本
     */
    private String version = "1.0.0";

    /**
     * 模板作者
     */
    private String author;

    /**
     * 模板备注
     */
    @Size(max = 500, message = "模板备注长度不能超过500个字符")
    private String notes;
}