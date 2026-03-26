package com.auraboot.framework.meta.entity;

import com.auraboot.framework.application.typehandler.JsonNodeTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.Instant;

/**
 * Named Query version snapshot.
 * Auto-created when a query transitions to PUBLISHED.
 */
@Data
@TableName(value = "ab_named_query_version", autoResultMap = true)
public class NamedQueryVersion {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("pid")
    private String pid;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("query_code")
    private String queryCode;

    @TableField("version_no")
    private Integer versionNo;

    @TableField("from_sql")
    private String fromSql;

    @TableField(value = "base_where", typeHandler = JsonNodeTypeHandler.class, jdbcType = org.apache.ibatis.type.JdbcType.OTHER)
    private JsonNode baseWhere;

    @TableField(value = "default_order", typeHandler = JsonNodeTypeHandler.class, jdbcType = org.apache.ibatis.type.JdbcType.OTHER)
    private JsonNode defaultOrder;

    @TableField(value = "fields_snapshot", typeHandler = JsonNodeTypeHandler.class, jdbcType = org.apache.ibatis.type.JdbcType.OTHER)
    private JsonNode fieldsSnapshot;

    @TableField(value = "policy", typeHandler = com.auraboot.framework.meta.typehandler.NamedQueryPolicyTypeHandler.class, jdbcType = org.apache.ibatis.type.JdbcType.OTHER)
    private NamedQueryPolicy policy;

    @TableField("description")
    private String description;

    @TableField("status")
    private String status;

    @TableField("published_at")
    private Instant publishedAt;

    @TableField("published_by")
    private Long publishedBy;

    @TableField("created_at")
    private Instant createdAt;
}
