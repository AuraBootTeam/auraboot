package com.auraboot.framework.userattribute.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

/**
 * Per-user free-form attribute used by the semantic layer's RLS engine.
 *
 * <p>Resolves {@code {user.<attribute_code>}} placeholders in
 * {@code AccessPolicy.sql_filter}. Multi-value attributes (e.g.
 * {@code "CN, US"}) are stored as a single comma-separated string;
 * {@code AccessPolicyCompiler} splits on commas and binds each as a
 * separate prepared-statement parameter.
 *
 * <p>Backed by {@code ab_user_attribute} — see migration
 * {@code 2026-05-29-user-attribute.sql} and ida/docs/25 §2 Phase 0 误判 #2.
 *
 * <p>Auraboot convention: {@code id BIGINT PRIMARY KEY} → {@link IdType#ASSIGN_ID}
 * (snowflake, not SERIAL). See ida/docs/25 §1.3 + AGENTS.md candidate gotcha.
 */
@Data
@TableName("ab_user_attribute")
public class AbUserAttribute {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** ULID. */
    private String pid;

    private Long tenantId;

    private Long userId;

    /** e.g. "allowed_regions", "department_code". */
    private String attributeCode;

    /** Single value or comma-separated list. */
    private String attributeValue;

    private String description;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    private Long createdBy;
    private Long updatedBy;

    @TableLogic
    private Boolean deletedFlag;
}
