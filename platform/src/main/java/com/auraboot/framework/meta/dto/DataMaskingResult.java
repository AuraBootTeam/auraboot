package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 数据脱敏结果DTO
 * 
 * @author AuraBoot Team
 * @since 2.1.0
 */
@Data
@Builder
public class DataMaskingResult {

    /**
     * 是否成功
     */
    private Boolean success;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 原始值
     */
    private Object originalValue;

    /**
     * 脱敏后的值
     */
    private Object maskedValue;

    /**
     * 是否应用了脱敏
     */
    private Boolean maskingApplied;

    /**
     * 脱敏规则
     */
    private String maskingRule;
}