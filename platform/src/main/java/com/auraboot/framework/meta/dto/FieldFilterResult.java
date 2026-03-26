package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 字段过滤结果DTO
 * 
 * @author AuraBoot Team
 * @since 2.1.0
 */
@Data
@Builder
public class FieldFilterResult {

    /**
     * 是否成功
     */
    private Boolean success;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 允许的字段列表
     */
    private List<String> allowedFields;

    /**
     * 被拒绝的字段列表 (HIDDEN)
     */
    private List<String> deniedFields;

    /**
     * 只读字段列表 (READONLY — can read but not write)
     */
    private List<String> readOnlyFields;

    /**
     * 字段脱敏规则映射
     */
    private Map<String, String> fieldMaskingRules;

    /**
     * 总字段数量
     */
    private Integer totalCount;

    /**
     * 允许的字段数量
     */
    private Integer allowedCount;

    /**
     * 被拒绝的字段数量
     */
    private Integer deniedCount;
}