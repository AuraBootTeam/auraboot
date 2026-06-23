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
 * Behavior analytics event (M1; SoT §5.5 frozen envelope). Event-first; the stable
 * join key is {@code uiElementId} (not a path). Persisted by /api/collect after
 * server-side tenant/user enrichment.
 */
@Data
@TableName("ab_behavior_event")
public class BehaviorEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String eventId;            // client ULID; idempotency key within tenant
    private String schemaVersion;
    private String eventName;
    private String eventCategory;
    private String source;             // autocapture|declared|server
    private String identityQuality;    // heuristic|declared|stable
    private Instant occurredAt;
    private Instant receivedAt;        // null on insert -> DB default
    private Long tenantId;             // server-enriched
    private Long userId;
    private String anonId;
    private String clientSessionId;
    private String interactionId;
    private String causedByEventId;
    private String traceId;
    private String sourceSpanId;
    private String runId;
    private String uiElementId;
    private String appId;
    private String pageId;
    private String blockId;
    private String elementCode;

    /** Arbitrary event props; stored as jsonb (typeHandler required, see check-jsonb-typehandler). */
    @TableField(value = "props", typeHandler = JsonbStringTypeHandler.class, jdbcType = JdbcType.OTHER)
    private String props;

    private String consentState;
    private String consentVersion;
    private String samplingUnit;
    private java.math.BigDecimal samplingProbability;
    private String producerName;
    private String producerVersion;
    private Instant createdAt;
}
