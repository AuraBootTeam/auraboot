package com.auraboot.framework.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;

/**
 * Registers Micrometer metrics for all {@link ThreadPoolTaskExecutor} beans.
 * <p>
 * Exposes standard executor metrics per pool:
 * {@code executor_pool_size_threads}, {@code executor_active_threads},
 * {@code executor_queued_tasks}, {@code executor_completed_tasks}, etc.
 */
@Slf4j
@Configuration
public class ThreadPoolMetricsConfig {

    private final ApplicationContext applicationContext;
    private final MeterRegistry meterRegistry;

    public ThreadPoolMetricsConfig(ApplicationContext applicationContext, MeterRegistry meterRegistry) {
        this.applicationContext = applicationContext;
        this.meterRegistry = meterRegistry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerThreadPoolMetrics() {
        Map<String, ThreadPoolTaskExecutor> executors =
                applicationContext.getBeansOfType(ThreadPoolTaskExecutor.class);

        executors.forEach((beanName, executor) -> {
            new ExecutorServiceMetrics(
                    executor.getThreadPoolExecutor(),
                    beanName,
                    null
            ).bindTo(meterRegistry);
            log.info("Registered Micrometer metrics for thread pool: {}", beanName);
        });

        log.info("Thread pool metrics registration complete: {} pools instrumented", executors.size());
    }
}
