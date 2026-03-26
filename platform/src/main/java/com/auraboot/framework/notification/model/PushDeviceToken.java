package com.auraboot.framework.notification.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

/**
 * Push device token entity for APNs/FCM push notifications.
 *
 * @since 6.4.0
 */
@Data
@TableName("ab_push_device_token")
public class PushDeviceToken {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("user_id")
    private Long userId;

    /** Platform: 'ios' or 'android'. */
    @TableField("platform")
    private String platform;

    @TableField("push_token")
    private String pushToken;

    @TableField("device_id")
    private String deviceId;

    /** Token type: 'apns', 'fcm', or 'voip'. */
    @TableField("token_type")
    private String tokenType;

    @TableField("app_version")
    private String appVersion;

    @TableField("os_version")
    private String osVersion;

    @TableField("is_valid")
    private Boolean isValid;

    @TableField("last_used_at")
    private Instant lastUsedAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    @TableField("deleted_flag")
    @TableLogic
    private Boolean deletedFlag;
}
