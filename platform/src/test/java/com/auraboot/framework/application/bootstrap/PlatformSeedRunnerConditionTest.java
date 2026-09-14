package com.auraboot.framework.application.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PlatformSeedRunnerConditionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(PlatformSeedService.class, () -> mock(PlatformSeedService.class))
            .withUserConfiguration(PlatformSeedRunner.class);

    @Test
    void coreOnlyRuntimeDoesNotRegisterStartupSeeder() {
        contextRunner
                .withPropertyValues("aura.application.mode=core-only")
                .run(context -> assertThat(context).doesNotHaveBean(PlatformSeedRunner.class));
    }

    @Test
    void compatibilityRuntimeKeepsHistoricalStartupSeeder() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(PlatformSeedRunner.class));
    }
}
