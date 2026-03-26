package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 字典数据加载结果DTO
 */
@Data
public class DictDataResult {

    /**
     * 字典编码
     */
    private String code;

    /**
     * 字典名称
     */
    private String name;

    /**
     * 字典类型
     */
    private String dictType;

    /**
     * 版本号
     */
    private String version;

    /**
     * 版本策略
     */
    private String versionStrategy;

    /**
     * 是否成功加载
     */
    private Boolean success;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 字典项列表
     */
    private List<DictItemData> items;

    /**
     * 字典项映射（key-value形式）
     */
    private Map<String, Object> itemMap;

    /**
     * 级联配置
     */
    private Object cascadeConfig;

    /**
     * 缓存信息
     */
    private CacheInfo cacheInfo;

    /**
     * 加载时间戳
     */
    private Long loadTimestamp;

    /**
     * 字典项数据
     */
    @Data
    public static class DictItemData {
        private String value;
        private String label;
        private String description;
        private Integer sortOrder;
        private Boolean enabled;
        private String parentValue;
        private Object extension;
    }

    /**
     * 缓存信息
     */
    @Data
    public static class CacheInfo {
        private Boolean cached;
        private Long cacheTime;
        private Long expireTime;
        private String cacheKey;
    }
}