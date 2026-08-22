package com.auraboot.framework.auth.entity;

import com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

@Data
@TableName(value = "ab_identity_provider_instance", autoResultMap = true)
public class IdentityProviderInstance {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String pid;
    private Long applicationId;
    private Long tenantId;
    private String code;
    private String displayName;
    private String providerType;
    private String status;
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String config;
    private String secretRef;
    private Instant createdAt;
    private Instant updatedAt;
}
