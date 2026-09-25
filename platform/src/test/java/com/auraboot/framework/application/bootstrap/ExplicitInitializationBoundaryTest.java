package com.auraboot.framework.application.bootstrap;

import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Startup runner inventory is deliberate: initialization must use explicit services. */
class ExplicitInitializationBoundaryTest {
    @Test
    void platformBootstrapPackageOnlyRegistersItsReadOnlyGuard() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(ApplicationRunner.class));
        var runners = scanner.findCandidateComponents("com.auraboot.framework.application.bootstrap")
                .stream().map(definition -> definition.getBeanClassName()).collect(Collectors.toSet());
        assertEquals(Set.of(OrphanMenuCheckRunner.class.getName()), runners);
    }

    @Test
    void schedulerDoesNotRegisterStartupDataInitialization() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(ApplicationRunner.class));
        assertTrue(scanner.findCandidateComponents("com.auraboot.framework.scheduler").isEmpty());
    }
}
