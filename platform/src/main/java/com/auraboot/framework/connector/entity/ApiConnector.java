package com.auraboot.framework.connector.entity;

import com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

/**
 * External API connector entity.
 *
 * @since 5.1.0
 */
@Data
@TableName(value = "ab_api_connector", autoResultMap = true)
public class ApiConnector {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("pid")
    private String pid;

    @TableField("name")
    private String name;

    @TableField("base_url")
    private String baseUrl;

    /**
     * Auth type: NONE / API_KEY / BEARER / BASIC / OAUTH2.
     */
    @TableField("auth_type")
    private String authType;

    @TableField(value = "auth_config", typeHandler = JsonbStringTypeHandler.class)
    private String authConfig;

    @TableField(value = "default_headers", typeHandler = JsonbStringTypeHandler.class)
    private String defaultHeaders;

    @TableField("timeout_ms")
    private Integer timeoutMs;

    @TableField(value = "retry_policy", typeHandler = JsonbStringTypeHandler.class)
    private String retryPolicy;

    @TableField("enabled")
    private Boolean enabled;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
