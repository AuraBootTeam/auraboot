package com.auraboot.framework.meta.entity;

import com.auraboot.framework.application.database.mybatis.StringArrayTypeHandler;
import com.auraboot.framework.application.typehandler.JsonNodeTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.Instant;

/**
 * Tamper-proof audit trail entity with SHA-256 chain hashing.
 * Each record includes the hash of the previous record, forming a
 * blockchain-like integrity chain per tenant.
 *
 * @since 6.1.0
 */
@Data
@TableName(value = "ab_audit_trail", autoResultMap = true)
public class AuditTrail {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("sequence_no")
    private Long sequenceNo;

    @TableField("event_type")
    private String eventType;

    @TableField("entity_type")
    private String entityType;

    @TableField("entity_id")
    private Long entityId;

    @TableField("command_code")
    private String commandCode;

    @TableField("operation_type")
    private String operationType;

    @TableField("actor_id")
    private Long actorId;

    @TableField("actor_name")
    private String actorName;

    @TableField("actor_ip")
    private String actorIp;

    @TableField("timestamp")
    private Instant timestamp;

    @TableField(value = "before_snapshot", typeHandler = JsonNodeTypeHandler.class, jdbcType = org.apache.ibatis.type.JdbcType.OTHER)
    private JsonNode beforeSnapshot;

    @TableField(value = "after_snapshot", typeHandler = JsonNodeTypeHandler.class, jdbcType = org.apache.ibatis.type.JdbcType.OTHER)
    private JsonNode afterSnapshot;

    @TableField(value = "changed_fields", typeHandler = StringArrayTypeHandler.class, jdbcType = org.apache.ibatis.type.JdbcType.OTHER)
    private String[] changedFields;

    @TableField(value = "metadata", typeHandler = JsonNodeTypeHandler.class, jdbcType = org.apache.ibatis.type.JdbcType.OTHER)
    private JsonNode metadata;

    @TableField("previous_hash")
    private String previousHash;

    @TableField("record_hash")
    private String recordHash;
}
