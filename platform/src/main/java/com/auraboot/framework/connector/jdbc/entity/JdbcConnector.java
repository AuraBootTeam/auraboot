package com.auraboot.framework.connector.jdbc.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("ab_jdbc_connector")
public class JdbcConnector {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String pid;
    private String name;
    private String jdbcUrl;
    private String username;
    /** Stored encrypted via FieldEncryptionService. Never returned to API consumers. */
    private String password;
    private Integer maxPoolSize;
    private Integer connectionTimeoutMs;
    private Boolean enabled;
    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
    private Long createdBy;
    private Long updatedBy;
}
