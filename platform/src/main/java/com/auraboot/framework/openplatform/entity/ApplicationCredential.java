package com.auraboot.framework.openplatform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("ab_application_credential")
public class ApplicationCredential {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String pid;
    private Long tenantId;
    private Long installationId;
    private String clientId;
    private String secretHash;
    private String status;
    private Instant expiresAt;
    private Instant lastUsedAt;
    private Instant revokedAt;
    private Instant createdAt;
}
