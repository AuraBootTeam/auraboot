package com.auraboot.framework.scheduler.xxl;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.scheduler.entity.ScheduledTask;
import com.auraboot.framework.scheduler.mapper.ScheduledTaskMapper;
import com.auraboot.framework.scheduler.service.TaskExecutor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuraBootScheduledTaskJobHandler {

    private final ObjectMapper objectMapper;
    private final ScheduledTaskMapper taskMapper;
    private final TaskExecutor taskExecutor;

    @XxlJob(XxlJobSchedulerEngine.EXECUTOR_HANDLER)
    public void executeFromXxlJob() {
        execute(XxlJobHelper.getJobParam());
    }

    public void execute(String rawPayload) {
        AuraBootScheduledTaskJobPayload payload = parse(rawPayload);
        if (payload.getTaskPid() == null || payload.getTaskPid().isBlank()) {
            throw new BusinessException("XXL-JOB payload must include taskPid");
        }

        ScheduledTask task = taskMapper.findByPid(payload.getTaskPid());
        if (task == null) {
            throw new BusinessException("Scheduled task not found for XXL-JOB payload: " + payload.getTaskPid());
        }

        taskExecutor.execute(task);
    }

    private AuraBootScheduledTaskJobPayload parse(String rawPayload) {
        if (rawPayload == null || rawPayload.isBlank()) {
            throw new BusinessException("XXL-JOB payload must include taskPid");
        }
        try {
            return objectMapper.readValue(rawPayload, AuraBootScheduledTaskJobPayload.class);
        } catch (JsonProcessingException e) {
            throw new BusinessException("Invalid XXL-JOB payload JSON: " + e.getOriginalMessage());
        }
    }
}
