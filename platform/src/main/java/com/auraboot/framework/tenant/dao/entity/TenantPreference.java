package com.auraboot.framework.tenant.dao.entity;

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
@TableName(value = "ab_tenant_preference", autoResultMap = true)
public class TenantPreference {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("pid")
    private String pid;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("preference_key")
    private String preferenceKey;

    @TableField(value = "preference_value", typeHandler = JsonNodeTypeHandler.class)
    private JsonNode preferenceValue;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
