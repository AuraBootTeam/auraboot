package com.auraboot.framework.im.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("ab_im_notification_preference")
public class ImNotificationPreference {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("model_code")
    private String modelCode; // NULL = global default

    @TableField("operation_type")
    private String operationType; // NULL = all, STATE_TRANSITION, CUSTOM

    @TableField("enabled")
    private Boolean enabled;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;
}
