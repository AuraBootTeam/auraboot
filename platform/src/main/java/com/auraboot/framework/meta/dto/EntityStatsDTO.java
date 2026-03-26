package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.util.Map;

/**
 * 实体统计信息DTO
 * 用于实体统计数据的传输
 * 
 * @author AuraBoot
 * @since 2024-01-01
 */
@Data
public class EntityStatsDTO {

    /**
     * 实体PID
     */
    private String entityPid;

    /**
     * 实体名称
     */
    private String entityName;

    /**
     * 实体显示名称
     */
    private String displayName;

    /**
     * 字段总数
     */
    private Long totalFields;

    /**
     * 必填字段数
     */
    private Long requiredFields;

    /**
     * 索引字段数
     */
    private Long indexedFields;

    /**
     * 唯一字段数
     */
    private Long uniqueFields;

    /**
     * 数据记录总数
     */
    private Long totalRecords;

    /**
     * 最近更新时间
     */
    private String lastUpdated;

    /**
     * 实体状态
     */
    private String status;

    /**
     * 按字段类型统计
     */
    private Map<String, Long> fieldTypeStats;

    /**
     * 按验证规则统计
     */
    private Map<String, Long> validationRuleStats;

    /**
     * 扩展统计信息
     */
    private Map<String, Object> extendedStats;
}