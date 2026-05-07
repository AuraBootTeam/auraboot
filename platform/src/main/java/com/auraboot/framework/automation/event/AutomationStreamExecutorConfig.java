package com.auraboot.framework.automation.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Bounded executor for {@link AutomationRunStreamPublisher} async fan-out
 * (E.1 Phase 1).
 *
 * <p>Sizing (per spec Q8 "buffer drop on overflow, don't fail node"):
 * <ul>
 *   <li>{@code corePoolSize=2} — typical workflow loads under steady-state
 *       have only a handful of concurrent LLM calls</li>
 *   <li>{@code maxPoolSize=4} — allows brief bursts without growing
 *       indefinitely</li>
 *   <li>{@code queueCapacity=256} — buffers spikes; 256 chunks at ~50 tokens
 *       each ≈ 12 KB peak per active stream, easily fits in memory</li>
 *   <li>{@link DroppingExecutorRejectionHandler} — drops oldest queued chunk
 *       and increments {@code aura_workflow_stream_chunk_dropped_total}; the
 *       LLM call thread never sees rejection</li>
 * </ul>
 *
 * <p>Spring's {@code @EnableAsync} elsewhere in {@code EventConfiguration}
 * picks up this bean by name when {@code @Async("llmStreamExecutor")} is
 * declared on {@link AutomationRunStreamPublisher#publishAsync}.
 */
@Slf4j
@Configuration
public class AutomationStreamExecutorConfig {

    @Bean("llmStreamExecutor")
    public Executor llmStreamExecutor(ApplicationContext applicationContext) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(256);
        executor.setThreadNamePrefix("LlmStream-");
        executor.setRejectedExecutionHandler(new DroppingExecutorRejectionHandler(applicationContext));
        executor.initialize();
        return executor;
    }
}
