package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * 业务模型响应DTO
 * 用于业务模型数据的返回
 */
@Data
@Builder
public class MetaModelDTO {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * PID
     */
    private String pid;

    /**
     * 模型编码
     */
    private String code;

    /**
     * 显示名称
     */
    private String displayName;

    /**
     * 描述信息
     */
    private String description;

    /**
     * 模型类型（ENTITY/VIEW/AGGREGATE等）
     */
    private String modelType;

    /**
     * Business object category (DOCUMENT, MASTER, TRANSACTION, ACTIVITY, REFERENCE, ENTITY)
     */
    private String modelCategory;

    /**
     * 版本号
     */
    private Integer version;

    /**
     * 是否为当前版本
     */
    private Boolean isCurrent;

    /**
     * 状态（DRAFT/PUBLISHED/ARCHIVED等）
     */
    private String status;

    /**
     * 租户ID
     */
    private Long tenantId;

      

    

    /**
     * Table name for the model.
     */
    private String tableName;

    /**
     * 扩展属性
     */
    private Map<String, Object> extension;

    /**
     * 绑定的字段列表
     */
    private List<MetaFieldDTO> fields;

    /**
     * 字段数量
     */
    private Integer fieldCount;

    /**
     * 创建者
     */
    private String createdBy;

    /**
     * 更新者
     */
    private String updatedBy;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;

    /**
     * 发布时间
     */
    private LocalDateTime publishedAt;

    /**
     * 发布者
     */
    private String publishedBy;

    /**
     * 版本说明
     */
    private String versionNote;

    /**
     * 是否为草稿状态
     * @return 是否为草稿状态
     */
    public boolean isDraft() {
        return StatusConstants.DRAFT.equals(status);
    }

    /**
     * 是否已发布
     * @return 是否已发布
     */
    public boolean isPublished() {
        return StatusConstants.PUBLISHED.equals(status);
    }

    /**
     * 是否已归档
     * @return 是否已归档
     */
    public boolean isArchived() {
        return StatusConstants.ARCHIVED.equals(status);
    }
}