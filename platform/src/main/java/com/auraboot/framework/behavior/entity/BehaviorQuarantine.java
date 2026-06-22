package com.auraboot.framework.behavior.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;

/**
 * A behavior event that could not be durably stored and was routed to the quarantine
 * topic (SoT §2.7 {@code aura.behavior.quarantine.v1}). Persisted by an async consumer
 * with no MetaContext, so {@code tenantId} is always set explicitly (the table is in the
 * tenant-line ignore list). The {@code rawEvent} payload is retained for replay.
 */
@Data
@TableName("ab_behavior_quarantine")
public class BehaviorQuarantine {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;             // resolved at the endpoint before enqueue
    private Long userId;
    private String anonId;
    private String eventId;            // nullable: malformed events may lack one
    private String eventName;          // nullable: malformed events may lack one
    private String reason;             // malformed_missing_event_id|..._event_name|constraint_violation
    private String detail;             // failure detail (e.g. truncated exception message)

    /** Original event payload (jsonb), retained for replay (typeHandler required, see check-jsonb-typehandler). */
    @TableField(value = "raw_event", typeHandler = JsonbStringTypeHandler.class, jdbcType = JdbcType.OTHER)
    private String rawEvent;

    private Instant quarantinedAt;     // null on insert -> DB default
    private Instant createdAt;
}
