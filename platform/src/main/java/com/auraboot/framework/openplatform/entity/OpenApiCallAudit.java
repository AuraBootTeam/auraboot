package com.auraboot.framework.openplatform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("ab_open_api_call_audit")
public class OpenApiCallAudit {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String pid;
    private Long tenantId;
    private String applicationPid;
    private String installationPid;
    private String tokenPid;
    private String requestId;
    private String httpMethod;
    private String requestPath;
    private Integer responseStatus;
    private Long durationMs;
    private String remoteAddress;
    private Instant occurredAt;
}
