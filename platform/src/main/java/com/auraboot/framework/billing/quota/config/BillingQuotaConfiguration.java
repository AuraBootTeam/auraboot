package com.auraboot.framework.billing.quota.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Activates quota-engine configuration properties.
 *
 * <p>Enables {@link BillingQuotaProperties} so that
 * {@code auraboot.billing.quota.*} YAML values are bound automatically.
 */
@Configuration
@EnableConfigurationProperties(BillingQuotaProperties.class)
public class BillingQuotaConfiguration {
}
