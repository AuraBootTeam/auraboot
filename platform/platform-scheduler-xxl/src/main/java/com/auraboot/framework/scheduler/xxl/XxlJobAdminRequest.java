package com.auraboot.framework.scheduler.xxl;

import lombok.Data;

@Data
public class XxlJobAdminRequest {

    private String taskPid;
    private Long tenantId;
    private String jobName;
    private String taskType;
    private String cronExpression;
    private String scheduleType;
    private String scheduleConf;
    private String executorAppName;
    private String executorHandler;
    private String executorPayload;
    private Integer maxRetries;
    private Long timeoutMs;
}
