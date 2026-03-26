package com.auraboot.framework.environment.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.util.Date;
import java.util.Map;

/**
 * Environment entity for multi-environment management.
 * Each tenant can define multiple environments (dev, staging, prod).
 */
@Data
@TableName(value = "ab_environment", autoResultMap = true)
public class Environment {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String pid;

    private Long tenantId;

    private Date createdAt;
    private Date updatedAt;

    /** Environment code, e.g. dev, staging, prod */
    private String code;

    /** Display name */
    private String name;

    private String description;

    /** API base URL for this environment */
    private String apiBaseUrl;

    /** Database connection info (JSONB). Sensitive fields should be encrypted at app level. */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> dbConnectionInfo;

    /** ACTIVE or INACTIVE */
    private String status;

    /** Whether this is the default environment */
    private Boolean isDefault;

    /** Display order */
    private Integer sortOrder;

    private Long createdBy;
    private Long updatedBy;

    private Boolean deletedFlag;
}
