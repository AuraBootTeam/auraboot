package com.auraboot.framework.eventpolicy.entity;

import com.auraboot.framework.decision.typehandler.JsonNodeTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;

/**
 * EventPolicy WRITE_AUDIT action trail row (docs/2.md §7) — the user-configured business audit entry
 * written when a policy rule with a WRITE_AUDIT action matches.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Data
@TableName(value = "ab_drt_action_audit", autoResultMap = true)
public class DrtActionAuditEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("pid")
    private String pid;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("rule_code")
    private String ruleCode;

    @TableField("action_type")
    private String actionType;

    @TableField("target")
    private String target;

    @TableField("message")
    private String message;

    @TableField(value = "payload_json", typeHandler = JsonNodeTypeHandler.class, jdbcType = JdbcType.OTHER)
    private JsonNode payloadJson;

    @TableField("idempotency_key")
    private String idempotencyKey;

    @TableField("created_at")
    private Instant createdAt;
}
