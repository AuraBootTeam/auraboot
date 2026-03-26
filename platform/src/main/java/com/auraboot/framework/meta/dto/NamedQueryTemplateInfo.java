package com.auraboot.framework.meta.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 命名查询模板信息DTO
 * 
 * @author AuraBoot
 * @since 2024-12-24
 */
@Data
public class NamedQueryTemplateInfo {

    /**
     * 模板ID
     */
    private Long templateId;

    /**
     * 查询ID
     */
    private Long queryId;

    /**
     * 模板名称
     */
    private String templateName;

    /**
     * 模板描述
     */
    private String templateDescription;

    /**
     * 模板类型
     */
    private String templateType;

    /**
     * 模板内容
     */
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
    private Boolean isPublic;

    /**
     * 模板版本
     */
    private String version;

    /**
     * 模板作者
     */
    private String author;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;

    /**
     * 使用次数
     */
    private Long usageCount;

    /**
     * 最后使用时间
     */
    private LocalDateTime lastUsedAt;

    /**
     * 模板状态
     */
    private String status;

    /**
     * 模板备注
     */
    private String notes;
}