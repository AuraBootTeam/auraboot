package com.auraboot.framework.scheduler.xxl;

import lombok.Data;

import java.util.Map;

@Data
public class AuraBootScheduledTaskJobPayload {

    private String taskPid;
    private Long tenantId;
    private String traceId;
    private String triggerType;
    private Map<String, Object> params;
    private Integer shardIndex;
    private Integer shardTotal;
}
