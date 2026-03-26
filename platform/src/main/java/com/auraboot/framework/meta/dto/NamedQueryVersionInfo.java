package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 命名查询版本信息DTO
 * 
 * @author AuraBoot
 * @since 2024-12-24
 */
@Data
public class NamedQueryVersionInfo {

    /**
     * 版本ID
     */
    private Long versionId;

    /**
     * 查询ID
     */
    private Long queryId;

    /**
     * 版本号
     */
    private String version;

    /**
     * 版本描述
     */
    private String description;

    /**
     * 版本类型
     */
    private String versionType;

    /**
     * 是否主要版本
     */
    private Boolean isMajor;

    /**
     * 变更说明
     */
    private String changeLog;

    /**
     * 版本标签
     */
    private String tag;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 创建者
     */
    private String createdBy;

    /**
     * 是否当前版本
     */
    private Boolean isCurrent;

    /**
     * 版本状态
     */
    private String status;

    /**
     * 版本大小（字节）
     */
    private Long versionSize;

    /**
     * 版本校验和
     */
    private String checksum;

    /**
     * 创建者备注
     */
    private String creatorNotes;
}