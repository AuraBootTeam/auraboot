package com.auraboot.framework.auth.entity;

import com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

@Data
@TableName(value = "ab_external_identity_link", autoResultMap = true)
public class ExternalIdentityLink {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String pid;
    private Long applicationId;
    private Long identityProviderInstanceId;
    private Long tenantId;
    private Long userId;
    private String externalSubject;
    private String externalUsername;
    private String email;
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String claims;
    private Instant linkedAt;
    private Instant lastLoginAt;
    private Instant unlinkedAt;
}
