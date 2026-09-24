package com.auraboot.framework.user.dao.entity;

import com.auraboot.framework.application.typehandler.JsonNodeTypeHandler;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.Instant;

@Data
@TableName(value = "ab_user_application_preference", autoResultMap = true)
public class UserApplicationPreference {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String pid;
    private Long userId;
    private Long applicationId;
    private String preferenceKey;
    @TableField(value = "preference_value", typeHandler = JsonNodeTypeHandler.class)
    private JsonNode preferenceValue;
    private Integer schemaVersion;
    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
