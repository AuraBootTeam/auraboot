package com.auraboot.framework.event.config;

import com.auraboot.framework.event.transport.TransportType;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the AuraEventBus transport layer.
 *
 * <pre>
 * aura:
 *   event:
 *     transport: local   # local | redis | rabbitmq
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "aura.event")
public class EventBusProperties {

    /**
     * Which transport backend to use for inter-service events.
     * Defaults to LOCAL (in-process).
     */
    private TransportType transport = TransportType.LOCAL;
}
