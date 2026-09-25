package com.auraboot.framework.meta.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncTaskStartupRecoveryRunnerTest {
    @Test void taskRecoveryDoesNotRegisterAStartupWriter() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(ApplicationRunner.class));
        assertTrue(scanner.findCandidateComponents("com.auraboot.framework.meta.service.impl").isEmpty());
    }
}
