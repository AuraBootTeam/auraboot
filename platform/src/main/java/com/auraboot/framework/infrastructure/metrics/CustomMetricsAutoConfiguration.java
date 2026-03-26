package com.auraboot.framework.infrastructure.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Auto-configuration for custom business metrics.
 * Only activates when Micrometer is on the classpath.
 */
@Configuration
@ConditionalOnClass(MeterRegistry.class)
@ComponentScan(basePackageClasses = CustomMetricsAutoConfiguration.class)
public class CustomMetricsAutoConfiguration {
}
