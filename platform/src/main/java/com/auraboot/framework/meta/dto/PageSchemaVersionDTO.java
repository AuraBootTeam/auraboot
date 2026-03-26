package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 页面Schema版本信息DTO
 * 用于版本管理和历史记录展示
 * 
 * @author AuraBoot Framework
 * @since 1.0.0
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class PageSchemaVersionDTO {

    /**
     * 历史记录ID
     */
    private Long id;

    /**
     * 关联的页面Schema PID
     */
    private String pagePid;

    /**
     * 版本号
     */
    private Integer version;

    /**
     * 语义化版本号
     */
    private String semver;

    /**
     * 操作类型
     * CREATE - 创建
     * UPDATE - 更新
     * PUBLISH - 发布
     * ARCHIVE - 归档
     * DELETE - 删除
     * RESTORE - 恢复
     */
    private String operation;

    /**
     * 操作人PID
     */
    private String operatorPid;

    /**
     * 操作时间
     */
    private LocalDateTime operationTime;

    /**
     * 版本快照数据
     * 包含完整的页面配置信息
     */
    private Map<String, Object> snapshot;

    /**
     * 版本描述/变更原因
     */
    private String description;

    /**
     * 是否为当前版本
     */
    private Boolean isCurrent;

}