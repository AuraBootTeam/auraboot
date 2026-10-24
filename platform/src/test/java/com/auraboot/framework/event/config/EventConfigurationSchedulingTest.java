package com.auraboot.framework.event.config;

import com.auraboot.framework.event.context.ThreadContextScopes;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;

class EventConfigurationSchedulingTest {
    record Selected(TaskScheduler application, TaskScheduler lease) {}

    @TestConfiguration(proxyBeanMethods = false)
    @EnableScheduling
    static class Fixture {
        @Bean ThreadContextScopes scopes() { return new ThreadContextScopes(List.of()); }
        @Bean Selected selected(TaskScheduler application,
                @Qualifier("asyncTaskLeaseScheduler") TaskScheduler lease) { return new Selected(application, lease); }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
            .withUserConfiguration(EventConfiguration.class, Fixture.class)
            .withPropertyValues("spring.task.scheduling.pool.size=4");

    @Test void namedLeaseSchedulerDoesNotSuppressTheConfiguredApplicationScheduler() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasBean("taskScheduler");
            var application = context.getBean("taskScheduler", ThreadPoolTaskScheduler.class);
            var lease = context.getBean("asyncTaskLeaseScheduler", ThreadPoolTaskScheduler.class);
            assertThat(application.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(4);
            assertThat(lease.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
            assertThat(context.getBean(TaskScheduler.class)).isSameAs(application);
            assertThat(context.getBean(Selected.class)).isEqualTo(new Selected(application, lease));
            assertThat(application).isNotSameAs(lease);
        });
    }

    @Test void explicitApplicationSchedulerIsPreserved() {
        runner.withBean("customApplicationScheduler", ThreadPoolTaskScheduler.class, () -> {
            var scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(3);
            return scheduler;
        }).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean("taskScheduler");
            var application = context.getBean("customApplicationScheduler", ThreadPoolTaskScheduler.class);
            var lease = context.getBean("asyncTaskLeaseScheduler", ThreadPoolTaskScheduler.class);
            assertThat(context.getBean(TaskScheduler.class)).isSameAs(application);
            assertThat(context.getBean(Selected.class)).isEqualTo(new Selected(application, lease));
            assertThat(application.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(3);
            assertThat(lease.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
        });
    }

    @Test void busyApplicationJobsCannotDelayTheDedicatedLeaseHeartbeat() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasBean("taskScheduler");
            var application = context.getBean("taskScheduler", ThreadPoolTaskScheduler.class);
            var lease = context.getBean("asyncTaskLeaseScheduler", ThreadPoolTaskScheduler.class);
            var entered = new CountDownLatch(4);
            var release = new CountDownLatch(1);
            var heartbeat = new CountDownLatch(1);
            try {
                for (int i = 0; i < 4; i++) application.execute(() -> {
                    entered.countDown();
                    try { release.await(5, TimeUnit.SECONDS); }
                    // CATCH: test-owned blocking task only; preserve interruption during shutdown.
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                });
                assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
                lease.schedule(heartbeat::countDown, Instant.now());
                assertThat(heartbeat.await(1, TimeUnit.SECONDS)).isTrue();
                assertThat(release.getCount()).isEqualTo(1);
            } finally { release.countDown(); }
        });
    }
}
