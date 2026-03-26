package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 数据过滤统计DTO
 * 
 * @author AuraBoot Team
 * @since 2.1.0
 */
@Data
@Builder
public class DataFilterStatistics {

    /**
     * 原始记录数量
     */
    private Integer originalRecordCount;

    /**
     * 过滤后记录数量
     */
    private Integer filteredRecordCount;

    /**
     * 移除的记录数量
     */
    private Integer removedRecordCount;

    /**
     * 原始字段数量
     */
    private Integer originalFieldCount;

    /**
     * 过滤后字段数量
     */
    private Integer filteredFieldCount;

    /**
     * 移除的字段数量
     */
    private Integer removedFieldCount;

    /**
     * 脱敏字段数量
     */
    private Integer maskedFieldCount;

    /**
     * 过滤耗时（毫秒）
     */
    private Long filterTimeMs;

    /**
     * 脱敏耗时（毫秒）
     */
    private Long maskingTimeMs;
}