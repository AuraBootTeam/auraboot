package com.auraboot.framework.scheduler.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Preserves the historical startup initialization outside core-only compositions.
 * Core-only installations initialize these records explicitly through bootstrap.
 */
@Component
@Order(2)
@RequiredArgsConstructor
@ConditionalOnExpression("'${aura.application.mode:}' != 'core-only'")
public class SystemTaskInitializationRunner implements ApplicationRunner {

    private final SystemTaskInitializer systemTaskInitializer;

    @Override
    public void run(ApplicationArguments args) {
        systemTaskInitializer.initializeSystemTasks();
    }
}
