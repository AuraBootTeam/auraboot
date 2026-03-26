package com.auraboot.framework.infrastructure.http;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration properties for the shared HTTP client.
 */
@Data
@ConfigurationProperties(prefix = "aura.http")
public class HttpClientProperties {

    /** Connection timeout. */
    private Duration connectTimeout = Duration.ofSeconds(5);

    /** Read (socket) timeout. */
    private Duration readTimeout = Duration.ofSeconds(30);

    /** Maximum total connections in the pool. */
    private int maxConnections = 200;

    /** Maximum connections per route. */
    private int maxPerRoute = 50;

    /** Retry configuration. */
    private Retry retry = new Retry();

    @Data
    public static class Retry {
        /** Maximum retry attempts. */
        private int maxAttempts = 3;

        /** Backoff interval between retries. */
        private Duration backoff = Duration.ofSeconds(1);
    }
}
