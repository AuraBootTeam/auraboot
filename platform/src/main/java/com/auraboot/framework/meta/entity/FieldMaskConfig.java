package com.auraboot.framework.meta.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;

/**
 * Configurable field-level data masking rule.
 *
 * <p>Supported mask types:
 * <ul>
 *   <li>PHONE — 138****5678</li>
 *   <li>EMAIL — gh***@163.com</li>
 *   <li>ID_CARD — 3201**********1234</li>
 *   <li>BANK_CARD — ****5678</li>
 *   <li>NAME — first char + asterisks</li>
 *   <li>PARTIAL — configurable show first N / last M chars via mask_pattern "N,M"</li>
 *   <li>FULL — all asterisks</li>
 *   <li>CUSTOM — regex-based replacement via mask_pattern</li>
 * </ul>
 *
 * @since 5.2.0
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("ab_field_mask_config")
public class FieldMaskConfig {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("model_code")
    private String modelCode;

    @TableField("field_code")
    private String fieldCode;

    /**
     * PHONE, EMAIL, ID_CARD, BANK_CARD, NAME, PARTIAL, FULL, CUSTOM.
     */
    @TableField("mask_type")
    private String maskType;

    /**
     * For CUSTOM: regex pattern; for PARTIAL: "firstN,lastM" (e.g. "3,4").
     */
    @TableField("mask_pattern")
    private String maskPattern;

    @TableField("replacement_char")
    private String replacementChar;

    @TableField("apply_to_export")
    private Boolean applyToExport;

    @TableField("apply_to_list")
    private Boolean applyToList;

    @TableField("apply_to_detail")
    private Boolean applyToDetail;

    @TableField("enabled")
    private Boolean enabled;

    /**
     * Comma-separated role codes that bypass masking.
     */
    @TableField("exempt_roles")
    private String exemptRoles;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
