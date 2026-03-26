package com.auraboot.framework.email.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for email open/click tracking.
 *
 * <p>Bind via {@code aura.email.tracking.*} in application.yml / environment variables.
 *
 * @since 6.5.0
 */
@Data
@Component
@ConfigurationProperties(prefix = "aura.email.tracking")
public class EmailTrackingConfig {

    /**
     * Whether tracking pixels and link rewriting are enabled.
     * Defaults to {@code true}.
     */
    private boolean enabled = true;

    /**
     * Base URL used to construct tracking pixel and redirect URLs.
     * Must be publicly reachable by email clients.
     */
    private String baseUrl = "http://localhost:6443";
}
