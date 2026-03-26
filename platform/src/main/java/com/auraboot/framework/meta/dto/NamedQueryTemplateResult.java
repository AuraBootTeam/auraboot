package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 命名查询模板结果DTO
 * 
 * @author AuraBoot
 * @since 2024-12-24
 */
@Data
public class NamedQueryTemplateResult {

    /**
     * 操作是否成功
     */
    private Boolean success;

    /**
     * 操作消息
     */
    private String message;

    /**
     * 模板ID
     */
    private Long templateId;

    /**
     * 模板名称
     */
    private String templateName;

    /**
     * 模板版本
     */
    private String version;

    /**
     * 操作时间
     */
    private LocalDateTime operationTime;

    /**
     * 验证错误列表
     */
    private List<String> validationErrors;

    /**
     * 警告信息列表
     */
    private List<String> warnings;

    /**
     * 操作详情
     */
    private String operationDetails;

    public NamedQueryTemplateResult() {
        this.operationTime = LocalDateTime.now(ZoneOffset.UTC);
    }

    public NamedQueryTemplateResult(Boolean success, String message) {
        this();
        this.success = success;
        this.message = message;
    }

    public static NamedQueryTemplateResult success(String message) {
        return new NamedQueryTemplateResult(true, message);
    }

    public static NamedQueryTemplateResult failure(String message) {
        return new NamedQueryTemplateResult(false, message);
    }
}