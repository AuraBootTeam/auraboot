package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Map;

/**
 * 批量脱敏请求DTO
 *
 * @author AuraBoot
 * @since 2024-01-01
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchMaskingRequest {

    /**
     * 需要脱敏的数据列表
     */
    @NotEmpty(message = "数据列表不能为空")
    private List<Map<String, Object>> data;

    /**
     * 脱敏规则映射（字段名 -> 脱敏规则）
     */
    private Map<String, String> maskingRules;

    /**
     * 全局脱敏参数
     */
    private Map<String, Object> globalParams;

    /**
     * 是否保留原始数据结构
     */
    private Boolean preserveStructure;

    /**
     * 脱敏级别（LOW, MEDIUM, HIGH）
     */
    private String maskingLevel;

    /**
     * 排除字段列表
     */
    private List<String> excludeFields;

    /**
     * 包含字段列表（如果指定，只处理这些字段）
     */
    private List<String> includeFields;
}