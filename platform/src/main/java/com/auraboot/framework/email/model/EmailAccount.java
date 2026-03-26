package com.auraboot.framework.email.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

/**
 * Represents a connected Gmail mailbox (personal or shared).
 *
 * @since 6.5.0
 */
@Data
@TableName("ab_email_account")
public class EmailAccount {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("user_id")
    private Long userId;

    /** Account type: 'personal' or 'shared'. See {@link EmailConstants#ACCOUNT_TYPE_PERSONAL}. */
    @TableField("account_type")
    private String accountType;

    /** Email provider — currently only 'gmail'. */
    @TableField("provider")
    private String provider;

    @TableField("email_address")
    private String emailAddress;

    @TableField("display_name")
    private String displayName;

    @TableField("access_token")
    private String accessToken;

    @TableField("refresh_token")
    private String refreshToken;

    @TableField("token_expires_at")
    private Instant tokenExpiresAt;

    /** Sync mode: 'manual' or 'auto'. See {@link EmailConstants#SYNC_MODE_MANUAL}. */
    @TableField("sync_mode")
    private String syncMode;

    /** JSONB blob tracking last-sync history token and state. Stored as raw JSON string. */
    @TableField("sync_state")
    private String syncState;

    /** Account status: 'active', 'inactive', 'error'. */
    @TableField("status")
    private String status;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    @TableField("deleted_flag")
    @TableLogic
    private Boolean deletedFlag;
}
