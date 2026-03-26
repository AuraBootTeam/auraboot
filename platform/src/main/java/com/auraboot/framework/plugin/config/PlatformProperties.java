package com.auraboot.framework.plugin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Platform-level properties exposed for plugin validation.
 */
@Data
@Component
@ConfigurationProperties(prefix = "auraboot.platform")
public class PlatformProperties {

    /**
     * Current platform version in semver format (e.g., "1.0.0").
     */
    private String version = "1.0.0";
}
