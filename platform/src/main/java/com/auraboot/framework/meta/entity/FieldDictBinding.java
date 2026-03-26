package com.auraboot.framework.meta.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Field-Dictionary binding entity
 * 
 * Manages the relationship between fields and dictionaries.
 * Used for ENUM type fields that need to bind to a specific dictionary.
 * 
 * @author AuraBoot Team
 * @since 2.1.2
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("ab_meta_field_dict_binding")
public class FieldDictBinding {

    /**
     * Primary key (auto-increment)
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * Public identifier (UUID)
     */
    @TableField("pid")
    private String pid;

    /**
     * Field ID (internal reference)
     */
    @TableField("field_id")
    private Long fieldId;

    /**
     * Field PID (public identifier)
     */
    @TableField("field_pid")
    private String fieldPid;

    /**
     * Field code
     */
    @TableField("field_code")
    private String fieldCode;

    /**
     * Dictionary ID (internal reference)
     */
    @TableField("dict_id")
    private Long dictId;

    /**
     * Dictionary code
     */
    @TableField("dict_code")
    private String dictCode;

    /**
     * Whether the field is required
     */
    @TableField("is_required")
    @Builder.Default
    private Boolean isRequired = false;

    /**
     * Whether multiple values are allowed
     */
    @TableField("allow_multiple")
    @Builder.Default
    private Boolean allowMultiple = false;

    /**
     * Default value
     */
    @TableField("default_value")
    private String defaultValue;

    /**
     * Tenant ID
     */
    @TableField("tenant_id")
    private Long tenantId;



    /**
     * Created timestamp
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    /**
     * Updated timestamp
     */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    /**
     * Created by user ID
     */
    @TableField("created_by")
    private Long createdBy;

    /**
     * Updated by user ID
     */
    @TableField("updated_by")
    private Long updatedBy;

    /**
     * Soft delete flag
     */
    @TableField("deleted_flag")
    @TableLogic
    @Builder.Default
    private Boolean deletedFlag = false;

    /**
     * Remarks
     */
    @TableField("remark")
    private String remark;
}
