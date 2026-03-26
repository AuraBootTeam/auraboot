package com.auraboot.framework.meta.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * 字典更新请求DTO
 */
@Data
public class DictUpdateRequest {

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
     * 数据源类型
     */
    private String sourceType;

    /**
     * 数据源配置
     */
    private JsonNode sourceConfig;

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
     * 版本说明
     */
    private String versionNote;

    /**
     * 更新者
     */
    private String updatedBy;
}