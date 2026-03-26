package com.auraboot.framework.i18n.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * I18n Resource Entity
 * Stores internationalization resources for the platform.
 *
 * Supports three-layer architecture:
 * - model.*: Model/field labels, placeholders, descriptions
 * - action.*: Common action button labels
 * - page.*: Page-specific overrides
 * - message.*: Toast/dialog messages
 * - table.*: Table common texts
 *
 * @author AuraBoot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("ab_i18n_resource")
public class I18nResource {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("pid")
    private String pid;

    @TableField("tenant_id")
    private Long tenantId;

    /**
     * I18n key following pattern: {scope}.{type}.{identifier}.{semantic}
     * Examples:
     * - model.device.name.label
     * - action.create
     * - message.delete.confirm.title
     * - table.noData
     */
    @TableField("i18n_key")
    private String i18nKey;

    /**
     * Language code: zh-CN, en-US, ja-JP, etc.
     */
    @TableField("lang")
    private String lang;

    /**
     * Translated text value
     */
    @TableField("value")
    private String value;

    /**
     * Source of the translation:
     * - model: Auto-generated from model/field displayName
     * - page: Page-specific override
     * - action: Common action layer
     * - system: Platform preset
     * - ai: AI-generated translation
     * - import: Imported from external file
     */
    @TableField("source")
    private String source;

    /**
     * Referenced entity type for model-derived keys:
     * - model: Reference to ab_meta_model
     * - field: Reference to ab_meta_field
     * - page: Reference to ab_page_schema
     */
    @TableField("ref_type")
    private String refType;

    /**
     * Referenced entity ID
     */
    @TableField("ref_id")
    private Long refId;

    /**
     * Status:
     * - DRAFT: Pending review (initial state for human-submitted translations)
     * - REVIEW: Submitted for review, awaiting approval
     * - APPROVED: Active and used in production
     * - DEPRECATED: Obsolete, kept for history
     */
    @TableField("status")
    private String status;

    /**
     * Rejection reason, set when reviewer rejects a REVIEW translation back to DRAFT
     */
    @TableField("reject_reason")
    private String rejectReason;

    /**
     * User ID who approved or rejected the translation
     */
    @TableField("reviewed_by")
    private Long reviewedBy;

    /**
     * Timestamp when the translation was reviewed (approved or rejected)
     */
    @TableField("reviewed_at")
    private Instant reviewedAt;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;

    @TableField("created_by")
    private Long createdBy;

    @TableField("updated_by")
    private Long updatedBy;

    @TableField("deleted_flag")
    @TableLogic(value = "false", delval = "true")
    private Boolean deletedFlag;

    // ==================== Constants ====================

    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_REVIEW = "review";
    public static final String STATUS_APPROVED = "approved";
    public static final String STATUS_DEPRECATED = "deprecated";

    public static final String SOURCE_MODEL = "model";
    public static final String SOURCE_PAGE = "page";
    public static final String SOURCE_ACTION = "action";
    public static final String SOURCE_SYSTEM = "system";
    public static final String SOURCE_AI = "ai";
    public static final String SOURCE_IMPORT = "import";

    public static final String REF_TYPE_MODEL = "model";
    public static final String REF_TYPE_FIELD = "field";
    public static final String REF_TYPE_PAGE = "page";

    public static final String LANG_ZH_CN = "zh-CN";
    public static final String LANG_EN_US = "en-US";
    public static final String LANG_JA_JP = "ja-JP";
}
