package com.auraboot.framework.openplatform.entity;

import com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;

@Data
@TableName(value = "ab_application_access_token", autoResultMap = true)
public class ApplicationAccessToken {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String pid;
    private Long tenantId;
    private Long installationId;
    private Long credentialId;
    @lombok.ToString.Exclude
    private String tokenHash;
    private String tokenKind = "oauth";
    private String tokenName;
    private Boolean neverExpires = false;
    private Instant acceptUntil;
    private Instant rotatedAt;
    private String replacedByPid;
    private String managementKey;
    @TableField(value = "scopes", typeHandler = JsonbStringTypeHandler.class, jdbcType = JdbcType.OTHER)
    private String scopes;
    private String audience;
    private Instant issuedAt;
    private Instant expiresAt;
    private Instant lastUsedAt;
    private Instant revokedAt;
}
