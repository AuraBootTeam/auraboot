package com.auraboot.framework.bpm.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.auraboot.framework.plugin.typehandler.PluginSettingsTypeHandler;
import lombok.*;
import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "ab_event_log", autoResultMap = true)
public class EventLogEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventId;
    private Long tenantId;
    private String eventType;
    private String sourceType;
    private String processKey;
    private String instanceId;
    private String nodeId;

    @TableField(typeHandler = PluginSettingsTypeHandler.class)
    private Map<String, Object> payload;

    private String status;          // PUBLISHED | CONSUMED | FAILED | DLQ
    private Integer retryCount;
    private String errorMessage;
    private Instant createdAt;
    private Instant consumedAt;
}
