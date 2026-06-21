package com.auraboot.framework.permission.capability;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * Persisted capability declaration (ab_capability), imported from a plugin's capabilities.json.
 * {@code includes} / {@code unmasksFields} are stored comma-separated.
 */
@Data
@TableName("ab_capability")
public class CapabilityRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String pid;

    private Long tenantId;

    private String code;

    @TableField("group_name")
    private String groupName;

    private String name;

    @TableField("name_en")
    private String nameEn;

    private String description;

    /** Comma-separated permission codes. */
    private String includes;

    private String tier;

    private Boolean sensitive;

    /** Comma-separated model.field codes. */
    @TableField("unmasks_fields")
    private String unmasksFields;

    @TableField("order_no")
    private Integer orderNo;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
