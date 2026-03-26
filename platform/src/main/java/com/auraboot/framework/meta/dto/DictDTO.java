package com.auraboot.framework.meta.dto;

import com.auraboot.framework.meta.entity.payload.DataSourceItemBean;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 字典响应DTO
 * 继承AbstractResponse获得通用字段
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class DictDTO extends AbstractResponse {

    /**
     * 字典编码
     */
    private String code;

    /**
     * 字典名称
     */
    private String name;

    /**
     * 字典描述
     */
    private String description;

    /**
     * 字典类型
     */
    private String dictType;


    /**
     * 字典项数据（兼容原数据源格式）
     */
    private List<DataSourceItemBean> items;

    /**
     * 版本策略
     */
    private String versionStrategy;

    /**
     * 固定版本号
     */
    private String pinnedVersion;

    /**
     * 级联配置
     */
    private JsonNode cascadeConfig;

    /**
     * 缓存配置
     */
    private JsonNode cacheConfig;

    /**
     * 扩展属性
     */
    private JsonNode extendedProps;

    /**
     * 排序权重
     */
    private Integer sortWeight;

    /**
     * 标签
     */
    private String tags;

    /**
     * 是否启用
     */
    private Boolean enabled;

    /**
     * 是否系统字典
     */
    private Boolean isSystem;

    /**
     * 是否已发布
     */
    private Boolean isPublished;

    /**
     * 发布时间
     */
    private LocalDateTime publishedAt;

    /**
     * 版本说明
     */
    private String versionNote;

    /**
     * Response remark or hint message
     */
    private String remark;

    /**
     * 创建者
     */
    private String createdBy;

    /**
     * 更新者
     */
    private String updatedBy;
}
