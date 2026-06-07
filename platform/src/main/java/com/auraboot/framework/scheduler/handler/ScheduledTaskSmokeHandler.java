package com.auraboot.framework.scheduler.handler;

import com.auraboot.framework.scheduler.entity.ScheduledTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component("scheduledTaskSmokeHandler")
public class ScheduledTaskSmokeHandler {

    public void execute(ScheduledTask task) {
        log.info("Scheduled task smoke handler executed: pid={}, name={}", task.getPid(), task.getName());
    }
}
