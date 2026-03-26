package com.auraboot.framework.bpm.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.auraboot.framework.bpm.typehandler.JsonListMapTypeHandler;
import lombok.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.auraboot.framework.common.constant.StatusConstants;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "ab_sla_record", autoResultMap = true)
public class SlaRecordEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String pid;
    private Long tenantId;
    private String slaConfigId;           // References ab_sla_config.pid
    private String processInstanceId;
    private String taskId;
    private String nodeId;

    private Instant startTime;
    private Instant deadlineTime;
    private Instant completedTime;

    private String status;                // RUNNING | WARNING | PAUSED | OVERDUE | COMPLETED | CANCELLED
    private Integer currentWarningLevel;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Instant pausedAt;
    @Builder.Default
    private Long totalPausedMs = 0L;

    @TableField(typeHandler = JsonListMapTypeHandler.class)
    private List<Map<String, Object>> warningHistory;

    private Instant createdAt;
    private Instant updatedAt;

    public boolean isActive() {
        return StatusConstants.RUNNING.equals(status) || "warning".equals(status) || "paused".equals(status);
    }

    public boolean isPaused() {
        return "paused".equals(status);
    }

    public boolean isOverdue() {
        return "overdue".equals(status);
    }
}
