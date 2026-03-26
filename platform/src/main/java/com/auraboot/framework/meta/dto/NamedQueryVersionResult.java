package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 命名查询版本操作结果DTO
 * 
 * @author AuraBoot
 * @since 2024-12-24
 */
@Data
public class NamedQueryVersionResult {

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
     * 源版本ID
     */
    private Long fromVersionId;

    /**
     * 目标版本ID
     */
    private Long toVersionId;

    /**
     * 操作类型
     */
    private String operationType;

    /**
     * 操作时间
     */
    private LocalDateTime operationTime;

    /**
     * 操作详情
     */
    private String operationDetails;

    /**
     * 影响的记录数
     */
    private Integer affectedRecords;

    /**
     * 错误信息列表
     */
    private List<String> errors;

    /**
     * 警告信息列表
     */
    private List<String> warnings;

    public NamedQueryVersionResult() {
        this.operationTime = LocalDateTime.now(ZoneOffset.UTC);
    }

    public NamedQueryVersionResult(Boolean success, String message) {
        this();
        this.success = success;
        this.message = message;
    }

    public static NamedQueryVersionResult success(String message) {
        return new NamedQueryVersionResult(true, message);
    }

    public static NamedQueryVersionResult failure(String message) {
        return new NamedQueryVersionResult(false, message);
    }
}