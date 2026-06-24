package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.mapper.AsyncTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
public class AsyncTaskStartupRecoveryRunner implements ApplicationRunner {

    private static final String RESTART_ERROR_MESSAGE =
            "Application restarted before async task completed; task was marked failed on startup recovery";

    private final AsyncTaskMapper asyncTaskMapper;

    @Override
    public void run(ApplicationArguments args) {
        recoverRunningTasks();
    }

    void run() {
        recoverRunningTasks();
    }

    private void recoverRunningTasks() {
        int recovered = asyncTaskMapper.markRunningTasksFailedOnStartup(Instant.now(), RESTART_ERROR_MESSAGE);
        if (recovered > 0) {
            log.warn("Marked {} stale running async task(s) failed after application startup", recovered);
        }
    }
}
