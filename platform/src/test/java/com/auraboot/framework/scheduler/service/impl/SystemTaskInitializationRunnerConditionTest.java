package com.auraboot.framework.scheduler.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SystemTaskInitializationRunnerConditionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(SystemTaskInitializer.class, () -> mock(SystemTaskInitializer.class))
            .withUserConfiguration(SystemTaskInitializationRunner.class);

    @Test
    void coreOnlyRuntimeDoesNotRegisterStartupTaskInitializer() {
        contextRunner
                .withPropertyValues("aura.application.mode=core-only")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(SystemTaskInitializationRunner.class));
    }

    @Test
    void compatibilityRuntimeKeepsHistoricalStartupTaskInitializer() {
        contextRunner.run(context -> assertThat(context)
                .hasSingleBean(SystemTaskInitializationRunner.class));
    }
}
