package com.auraboot.framework.scheduler.xxl;

import com.auraboot.framework.scheduler.mapper.ScheduledTaskMapper;
import com.auraboot.framework.scheduler.service.SchedulerEngine;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class XxlJobSchedulerConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(XxlJobSchedulerConfiguration.class)
            .withBean(ScheduledTaskMapper.class, () -> mock(ScheduledTaskMapper.class))
            .withBean(XxlJobAdminClient.class, () -> mock(XxlJobAdminClient.class));

    private final ApplicationContextRunner contextRunnerWithoutAdminClient = new ApplicationContextRunner()
            .withUserConfiguration(XxlJobSchedulerConfiguration.class)
            .withBean(ScheduledTaskMapper.class, () -> mock(ScheduledTaskMapper.class));

    @Test
    void defaultLocalMode_doesNotCreateXxlSchedulerEngine() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(XxlJobSchedulerEngine.class);
            assertThat(context).doesNotHaveBean(SchedulerEngine.class);
        });
    }

    @Test
    void xxlMode_createsSchedulerEngine() {
        contextRunner
                .withPropertyValues("aura.scheduler.engine=xxl")
                .run(context -> {
                    assertThat(context).hasSingleBean(XxlJobSchedulerEngine.class);
                    assertThat(context).hasSingleBean(SchedulerEngine.class);
                });
    }

    @Test
    void xxlModeWithoutAdminClient_usesUnavailableFallbackClient() {
        contextRunnerWithoutAdminClient
                .withPropertyValues("aura.scheduler.engine=xxl")
                .run(context -> {
                    assertThat(context).hasSingleBean(XxlJobAdminClient.class);
                    assertThat(context.getBean(XxlJobAdminClient.class))
                            .isInstanceOf(UnavailableXxlJobAdminClient.class);
                    assertThat(context).hasSingleBean(XxlJobSchedulerEngine.class);
                });
    }

    @Test
    void xxlModeWithAdminAddress_usesHttpAdminClient() {
        contextRunnerWithoutAdminClient
                .withPropertyValues(
                        "aura.scheduler.engine=xxl",
                        "aura.scheduler.xxl.admin-addresses=http://xxl-admin:8080/xxl-job-admin"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(XxlJobAdminClient.class);
                    assertThat(context.getBean(XxlJobAdminClient.class))
                            .isInstanceOf(XxlJobAdminHttpClient.class);
                    assertThat(context).hasSingleBean(XxlJobSchedulerEngine.class);
                });
    }

    @Test
    void bindsXxlJobProperties() {
        contextRunner
                .withPropertyValues(
                        "aura.scheduler.engine=xxl",
                        "aura.scheduler.xxl.admin-addresses=http://xxl-admin:8080/xxl-job-admin",
                        "aura.scheduler.xxl.admin-username=admin",
                        "aura.scheduler.xxl.admin-password=secret",
                        "aura.scheduler.xxl.access-token=test-token",
                        "aura.scheduler.xxl.executor-app-name=auraboot-platform"
                )
                .run(context -> {
                    XxlJobProperties properties = context.getBean(XxlJobProperties.class);
                    assertThat(properties.getAdminAddresses()).isEqualTo("http://xxl-admin:8080/xxl-job-admin");
                    assertThat(properties.getAdminUsername()).isEqualTo("admin");
                    assertThat(properties.getAdminPassword()).isEqualTo("secret");
                    assertThat(properties.getAccessToken()).isEqualTo("test-token");
                    assertThat(properties.getExecutorAppName()).isEqualTo("auraboot-platform");
                });
    }
}
