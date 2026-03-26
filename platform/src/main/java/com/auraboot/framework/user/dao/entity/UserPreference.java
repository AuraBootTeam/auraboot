package com.auraboot.framework.user.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.auraboot.framework.application.typehandler.JsonNodeTypeHandler;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.Instant;

@Data
@TableName(value = "ab_user_preference", autoResultMap = true)
public class UserPreference {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("pid")
    private String pid;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("user_id")
    private Long userId;

    @TableField("preference_key")
    private String preferenceKey;

    @TableField(value = "preference_value", typeHandler = JsonNodeTypeHandler.class)
    private JsonNode preferenceValue;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
