package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 命名查询字段操作结果DTO
 * 
 * @author AuraBoot
 * @since 2024-12-24
 */
@Data
public class NamedQueryFieldResult {

    /**
     * 操作是否成功
     */
    private Boolean success;

    /**
     * 操作消息
     */
    private String message;

    /**
     * 查询ID
     */
    private Long queryId;

    /**
     * 字段ID
     */
    private Long fieldId;

    /**
     * 字段编码
     */
    private String fieldCode;

    /**
     * 操作类型
     */
    private String operationType;

    /**
     * 操作时间
     */
    private LocalDateTime operationTime;

    /**
     * 字段信息
     */
    private NamedQueryFieldDTO fieldInfo;

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

    public NamedQueryFieldResult() {
        this.operationTime = LocalDateTime.now(ZoneOffset.UTC);
    }

    public NamedQueryFieldResult(Boolean success, String message) {
        this();
        this.success = success;
        this.message = message;
    }

    public static NamedQueryFieldResult success(String message) {
        return new NamedQueryFieldResult(true, message);
    }

    public static NamedQueryFieldResult failure(String message) {
        return new NamedQueryFieldResult(false, message);
    }
}