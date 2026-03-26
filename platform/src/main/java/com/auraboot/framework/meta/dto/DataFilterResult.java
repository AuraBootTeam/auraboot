package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 数据过滤结果DTO
 * 
 * @author AuraBoot Team
 * @since 2.1.0
 */
@Data
@Builder
public class DataFilterResult {

    /**
     * 是否成功
     */
    private Boolean success;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 原始数据
     */
    private List<Map<String, Object>> originalData;

    /**
     * 过滤后的数据
     */
    private List<Map<String, Object>> filteredData;

    /**
     * 过滤统计信息
     */
    private DataFilterStatistics statistics;

    /**
     * 过滤时间
     */
    private LocalDateTime filterTime;

    /**
     * 应用的过滤规则
     */
    private List<String> appliedRules;

    /**
     * 脱敏字段列表
     */
    private List<String> maskedFields;
}