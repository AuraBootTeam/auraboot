package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 数据字典实体与字段关联查询请求DTO
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DictEntityFieldQueryRequest extends AbstractQueryRequest {

    /**
     * 关联的数据字典实体ID
     */
    private Long entityId;

    /**
     * 关联的字段定义ID
     */
    private Long fieldId;

    /**
     * 字段在实体中的排序
     */
    private Integer fieldOrder;

    /**
     * 是否包含实体信息
     */
    private Boolean includeEntityInfo;

    /**
     * 是否包含字段信息
     */
    private Boolean includeFieldInfo;

    /**
     * 创建时间开始
     */
    private java.time.LocalDateTime createdAtStart;

    /**
     * 创建时间结束
     */
    private java.time.LocalDateTime createdAtEnd;

    /**
     * 更新时间开始
     */
    private java.time.LocalDateTime updatedAtStart;

    /**
     * 更新时间结束
     */
    private java.time.LocalDateTime updatedAtEnd;

    /**
     * 排序字段
     */
    private String orderBy;

    /**
     * 排序方向
     */
    private String orderDirection;

    /**
     * 最小字段顺序
     */
    private Integer minFieldOrder;

    /**
     * 最大字段顺序
     */
    private Integer maxFieldOrder;

    /**
     * 实体ID列表
     */
    private java.util.List<Long> entityIds;

    /**
     * 字段ID列表
     */
    private java.util.List<Long> fieldIds;

    /**
     * 实体字段统计请求
     */
    @Data
    public static class EntityFieldStatsRequest {
        private Long entityId;
        private String timeRange;
        private java.util.List<Long> entityIds;
        private Integer minFieldCount;
        private Integer maxFieldCount;
        private Boolean includeFieldDetails;
    }

    /**
     * 字段使用统计请求
     */
    @Data
    public static class FieldUsageStatsRequest {
        private Long fieldId;
        private String timeRange;
        private java.util.List<Long> fieldIds;
        private Integer minUsageCount;
        private Integer maxUsageCount;
        private Boolean includeEntityDetails;
    }
}
