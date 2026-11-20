package com.auraboot.framework.scheduler.service.impl;

import com.auraboot.framework.common.util.TenantClock;
import com.auraboot.framework.scheduler.entity.ScheduledTask;
import com.auraboot.framework.scheduler.mapper.ScheduledTaskMapper;
import com.auraboot.framework.scheduler.service.TaskExecutor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Map;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Claim duration and registration policy only; actual recovery is verified by the product runtime gate. */
class DatabaseSchedulerIntervalClaimTest {
    private final TaskExecutor executor=mock(TaskExecutor.class);
    private final TaskScheduler scheduler=mock(TaskScheduler.class);
    private final StringRedisTemplate redis=mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String,String> values=mock(ValueOperations.class);
    private final DatabaseSchedulerEngine engine=new DatabaseSchedulerEngine(
            mock(ScheduledTaskMapper.class),executor,scheduler,mock(TenantClock.class));

    private ScheduledTask task(long interval) {
        var task=new ScheduledTask();task.setPid("claim-cadence-test");task.setTaskType("interval");
        task.setIntervalMs(interval);return task;
    }
    private Runnable register(ScheduledTask task) {
        ReflectionTestUtils.setField(engine,"claimRedis",redis);when(redis.opsForValue()).thenReturn(values);
        engine.scheduleTask(task);var callback=ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleAtFixedRate(callback.capture(),eq(Duration.ofMillis(task.getIntervalMs())));
        return callback.getValue();
    }
    @Test void shortIntervalDoesNotAcquireAMinuteLongClaim() {
        var task=task(5000);var callback=register(task);
        when(values.setIfAbsent(anyString(),anyString(),any(Duration.class))).thenReturn(true);
        callback.run();
        verify(values).setIfAbsent(eq("aura:sched:claim:claim-cadence-test"),anyString(),eq(Duration.ofSeconds(5)));
        verify(executor).execute(task);
    }
    @Test void managedEnvironmentPrefixSeparatesIndependentRuntimeClaims() {
        try(var context=new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("owned-runtime",Map.of("REDIS_KEY_PREFIX","aura:quote:7:")));
            context.registerBean(DatabaseSchedulerEngine.class,()->engine);context.refresh();
            var task=task(5000);var callback=register(task);
            when(values.setIfAbsent(anyString(),anyString(),any(Duration.class))).thenReturn(true);
            callback.run();
            verify(values).setIfAbsent(eq("aura:quote:7:sched:claim:claim-cadence-test"),anyString(),eq(Duration.ofSeconds(5)));
            verify(executor).execute(task);
        }
    }
    @Test void explicitClusterPrefixOverridesTheManagedEnvironmentDefault() {
        try(var context=new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("cluster",Map.of(
                    "REDIS_KEY_PREFIX","aura:quote:7:","aura.scheduler.claim-key-prefix","stable:cluster:sched:claim:")));
            context.registerBean(DatabaseSchedulerEngine.class,()->engine);context.refresh();
            var task=task(5000);var callback=register(task);
            when(values.setIfAbsent(anyString(),anyString(),any(Duration.class))).thenReturn(true);
            callback.run();
            verify(values).setIfAbsent(eq("stable:cluster:sched:claim:claim-cadence-test"),anyString(),eq(Duration.ofSeconds(5)));
            verify(executor).execute(task);
        }
    }
    @Test void duplicateOrUnknownClaimsDoNotRunHandlers() {
        var task=task(5000);var callback=register(task);
        when(values.setIfAbsent(anyString(),anyString(),any(Duration.class))).thenReturn(true,false,null,true);
        for(int i=0;i<4;i++)callback.run();
        verify(executor,times(2)).execute(task);
    }
    @Test void longIntervalsRetainTheExistingMaximumClaimDuration() {
        var task=task(300000);var callback=register(task);
        when(values.setIfAbsent(anyString(),anyString(),any(Duration.class))).thenReturn(true);
        callback.run();
        verify(values).setIfAbsent(anyString(),anyString(),eq(Duration.ofSeconds(60)));
        verify(executor).execute(task);
    }
    @Test void redisFailureKeepsTheExistingSingleInstanceFallback() {
        var task=task(5000);var callback=register(task);
        when(values.setIfAbsent(anyString(),anyString(),any(Duration.class))).thenThrow(new IllegalStateException("unavailable"));
        callback.run();verify(executor).execute(task);
    }
}
