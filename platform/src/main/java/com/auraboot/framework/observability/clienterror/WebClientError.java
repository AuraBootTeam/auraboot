package com.auraboot.framework.observability.clienterror;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * Web front-end client error — an uncaught JS error or unhandled promise rejection
 * reported by the browser, so front-end failures are visible in the in-app
 * troubleshooting center instead of vanishing.
 */
@Data
@TableName("ab_web_client_error")
public class WebClientError {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("user_id")
    private Long userId;

    @TableField("session_id")
    private String sessionId;

    @TableField("trace_id")
    private String traceId;

    /** error | unhandledrejection */
    @TableField("error_type")
    private String errorType;

    @TableField("message")
    private String message;

    @TableField("stack")
    private String stack;

    @TableField("page_url")
    private String pageUrl;

    @TableField("user_agent")
    private String userAgent;

    @TableField("app_version")
    private String appVersion;

    @TableField("client_timestamp")
    private Instant clientTimestamp;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;
}
