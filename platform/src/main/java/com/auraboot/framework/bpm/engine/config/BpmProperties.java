package com.auraboot.framework.bpm.engine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the BPM abstraction layer.
 *
 * <pre>
 * aura:
 *   bpm:
 *     engine: smartengine   # smartengine | camunda | flowable
 * </pre>
 */
@ConfigurationProperties(prefix = "aura.bpm")
public class BpmProperties {

    /**
     * The BPM engine type to activate.
     * Supported values: smartengine, camunda, flowable.
     * Default: smartengine.
     */
    private String engine = "smartengine";

    public String getEngine() {
        return engine;
    }

    public void setEngine(String engine) {
        this.engine = engine;
    }
}
