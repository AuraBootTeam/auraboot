package com.auraboot.framework.application.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Async executor for {@link AdminAuditService}. The audit write is best-effort:
 * a flooded queue silently discards rather than blocking the request thread.
 *
 * <p>{@code @EnableAsync} is intentionally omitted here — it is already declared
 * on {@code EventConfiguration} which is always present in the Spring context.
 * Duplicating the annotation is harmless but adds noise; one activation is sufficient.
 */
@Configuration
public class AdminAuditConfig {

    @Bean(name = "adminAuditExecutor")
    public Executor adminAuditExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("admin-audit-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        return executor;
    }
}
