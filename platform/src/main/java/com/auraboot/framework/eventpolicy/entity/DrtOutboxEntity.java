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
 * EventPolicy transactional outbox row (docs/2.md §9). Written PENDING inside the save transaction;
 * a processor runs the bound policy after commit and marks PROCESSED/FAILED.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Data
@TableName(value = "ab_drt_outbox", autoResultMap = true)
public class DrtOutboxEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("pid")
    private String pid;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("event_id")
    private String eventId;

    @TableField("event_type")
    private String eventType;

    @TableField("target_type")
    private String targetType;

    @TableField("target_key")
    private String targetKey;

    @TableField(value = "context_json", typeHandler = JsonNodeTypeHandler.class, jdbcType = JdbcType.OTHER)
    private JsonNode contextJson;

    @TableField("status")
    private String status;

    @TableField("attempts")
    private Integer attempts;

    @TableField("last_error")
    private String lastError;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("processed_at")
    private Instant processedAt;
}
