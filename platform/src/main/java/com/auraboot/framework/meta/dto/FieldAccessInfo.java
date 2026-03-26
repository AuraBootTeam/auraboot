package com.auraboot.framework.meta.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 字段权限信息
 *
 * @author AuraBoot Team
 * @since 2.1.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FieldAccessInfo {

    /**
     * 字段编码
     */
    private String code;

    /**
     * 是否可读
     */
    private Boolean readable;

    /**
     * 是否可写
     */
    private Boolean writable;

    /**
     * 是否可见
     */
    private Boolean visible;

    /**
     * 是否需要数据脱敏
     */
    private Boolean maskingRequired;

    /**
     * 脱敏规则
     */
    private String maskingRule;

    // Convenience setters
    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public void setMaskingRequired(boolean maskingRequired) {
        this.maskingRequired = maskingRequired;
    }
}
