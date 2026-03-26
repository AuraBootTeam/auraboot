package com.auraboot.framework.event.config;

import com.auraboot.framework.event.transport.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Event configuration: thread pools + transport layer auto-config.
 * <p>
 * The active transport is selected by <code>aura.event.transport</code>:
 * <ul>
 *   <li><b>local</b> (default) — in-process pub-sub, zero external deps</li>
 *   <li><b>redis</b> — Redis Streams with consumer groups</li>
 *   <li><b>rabbitmq</b> — AMQP topic exchange (stub, needs spring-amqp)</li>
 * </ul>
 */
@Slf4j
@Configuration
@EnableAsync
@EnableConfigurationProperties(EventBusProperties.class)
public class EventConfiguration {
    
    /**
     * 异步事件处理线程池
     */
    @Bean("eventTaskExecutor")
    public Executor eventTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("Event-");
        executor.setTaskDecorator(new TenantAwareTaskDecorator());
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * Async export task thread pool.
     */
    @Bean("exportTaskExecutor")
    public Executor exportTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("Export-");
        executor.setTaskDecorator(new TenantAwareTaskDecorator());
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * General-purpose task executor for @Async("taskExecutor") usage.
     * Used by scheduler, capability sync, and other async operations.
     */
    @Bean("taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("task-");
        executor.setTaskDecorator(new TenantAwareTaskDecorator());
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * Unified async task framework thread pool.
     * Handles background jobs: exports, batch operations, MRP calculations, etc.
     */
    @Bean("asyncTaskExecutor")
    public Executor asyncTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("async-task-");
        executor.setTaskDecorator(new TenantAwareTaskDecorator());
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    // ── Transport beans (GAP-105) ───────────────────────────────────

    @Bean
    @ConditionalOnProperty(name = "aura.event.transport", havingValue = "redis")
    public EventBusTransport redisStreamTransport(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        log.info("Initializing Redis Streams event transport");
        return new RedisStreamTransport(redisTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "aura.event.transport", havingValue = "rabbitmq")
    public EventBusTransport rabbitMqTransport(ObjectMapper objectMapper) {
        log.info("Initializing RabbitMQ event transport (stub)");
        return new RabbitMqTransport(null, objectMapper);
    }

    /**
     * Fallback: local in-process transport when no external transport is configured.
     */
    @Bean
    @ConditionalOnMissingBean(EventBusTransport.class)
    public EventBusTransport localTransport() {
        log.info("Initializing local (in-process) event transport");
        return new LocalTransport();
    }
}