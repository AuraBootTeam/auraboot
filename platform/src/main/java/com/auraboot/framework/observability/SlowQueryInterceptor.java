package com.auraboot.framework.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.time.Duration;
import java.util.Properties;

/**
 * Native MyBatis {@link Interceptor} that detects and logs slow SQL queries.
 *
 * <p>Uses {@code invocation.proceed()} to measure actual execution time for both
 * SELECT (query) and INSERT/UPDATE/DELETE (update) statements. Slow queries are
 * logged at WARN level and recorded in Micrometer metrics.
 *
 * <p>Registered as a Spring bean — MyBatis auto-detects any {@link Interceptor}
 * bean in the application context and plugs it into the executor chain.
 *
 * <p>Why native Interceptor instead of MyBatis Plus InnerInterceptor:
 * {@code InnerInterceptor} exposes {@code beforeQuery()} only — it cannot wrap
 * the actual execution and measure elapsed time. Native Interceptor wraps
 * {@code invocation.proceed()} and captures the full round-trip duration.
 */
@Slf4j
@Intercepts({
    @Signature(type = Executor.class, method = "query",
        args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
    @Signature(type = Executor.class, method = "update",
        args = {MappedStatement.class, Object.class})
})
public class SlowQueryInterceptor implements Interceptor {

    private static final String METRIC_SLOW_QUERY_COUNT = "auraboot.sql.slow_query_count";
    private static final String METRIC_SLOW_QUERY_TIMER = "auraboot.sql.slow_query";

    private final long thresholdMs;
    private final boolean logParams;
    private final Counter slowQueryCounter;
    private final Timer slowQueryTimer;

    public SlowQueryInterceptor(long thresholdMs, boolean logParams, MeterRegistry registry) {
        this.thresholdMs = thresholdMs;
        this.logParams = logParams;
        this.slowQueryCounter = Counter.builder(METRIC_SLOW_QUERY_COUNT)
                .description("Number of slow SQL queries detected")
                .register(registry);
        this.slowQueryTimer = Timer.builder(METRIC_SLOW_QUERY_TIMER)
                .description("Duration of slow SQL queries")
                .register(registry);
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        long start = System.nanoTime();
        try {
            return invocation.proceed();
        } finally {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            if (elapsedMs >= thresholdMs) {
                recordSlowQuery(invocation, elapsedMs);
            }
        }
    }

    private void recordSlowQuery(Invocation invocation, long elapsedMs) {
        MappedStatement ms = (MappedStatement) invocation.getArgs()[0];
        String mapperId = ms.getId();

        slowQueryCounter.increment();
        slowQueryTimer.record(Duration.ofMillis(elapsedMs));

        if (logParams) {
            Object param = invocation.getArgs()[1];
            BoundSql boundSql = ms.getBoundSql(param);
            String sql = boundSql.getSql().replaceAll("\\s+", " ").trim();
            log.warn("Slow query [{}ms] mapper={} sql={}", elapsedMs, mapperId, sql);
        } else {
            log.warn("Slow query [{}ms] mapper={}", elapsedMs, mapperId);
        }
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {
        // Configuration is injected via constructor from Spring; not used here.
    }
}
