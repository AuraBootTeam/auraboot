package com.auraboot.framework.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers observability interceptors into the Spring MVC pipeline.
 */
@Configuration
public class ObservabilityConfig implements WebMvcConfigurer {

    private final ApiMetricsInterceptor apiMetricsInterceptor;

    public ObservabilityConfig(ApiMetricsInterceptor apiMetricsInterceptor) {
        this.apiMetricsInterceptor = apiMetricsInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiMetricsInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/actuator/**");
    }

    /**
     * Registers the slow-query interceptor as a Spring bean.
     * MyBatis auto-detects any {@link org.apache.ibatis.plugin.Interceptor} bean
     * and plugs it into the executor chain — no manual registration needed.
     */
    @Bean
    public SlowQueryInterceptor slowQueryInterceptor(
            MeterRegistry registry,
            @Value("${auraboot.performance.slow-query-threshold-ms:500}") long thresholdMs,
            @Value("${auraboot.performance.slow-query-log-params:true}") boolean logParams) {
        return new SlowQueryInterceptor(thresholdMs, logParams, registry);
    }

    /**
     * Registers the SQL count interceptor as a Spring bean.
     * Increments {@link SqlCountHolder} on every query/update execution,
     * enabling per-request SQL count tracking via {@link SqlCountFilter}.
     */
    @Bean
    public SqlCountInterceptor sqlCountInterceptor() {
        return new SqlCountInterceptor();
    }
}
