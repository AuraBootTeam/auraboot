package com.auraboot.framework.scheduler.xxl;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class XxlJobExecutorConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(XxlJobExecutorConfig.class);

    @Test
    void defaultLocalMode_doesNotCreateXxlExecutor() {
        contextRunner.run(context ->
                assertThat(context).doesNotHaveBean(XxlJobSpringExecutor.class));
    }

    @Test
    void xxlMode_createsXxlExecutor() {
        contextRunner
                .withPropertyValues(
                        "aura.scheduler.engine=xxl",
                        "aura.scheduler.xxl.admin-addresses=http://xxl-admin:8080/xxl-job-admin",
                        "aura.scheduler.xxl.access-token=test-token",
                        "aura.scheduler.xxl.executor-app-name=auraboot-platform"
                )
                .run(context ->
                        assertThat(context).hasSingleBean(XxlJobSpringExecutor.class));
    }
}
