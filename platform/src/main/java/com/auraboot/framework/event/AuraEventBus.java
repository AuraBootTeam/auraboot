package com.auraboot.framework.event;

import com.auraboot.framework.infrastructure.mq.MqProvider;
import com.auraboot.framework.infrastructure.mq.memory.InMemoryMqProvider;
import com.auraboot.framework.meta.context.SandboxContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashMap;
import java.util.Map;

/**
 * Central event bus facade for publishing domain events.
 *
 * Delegates to Spring's ApplicationEventPublisher for @EventListener consumers,
 * optionally to a PluginEventDispatcher for plugin-based listeners,
 * and optionally to a {@link MqProvider} for cross-process event distribution.
 *
 * @since 6.0.0
 */
@Slf4j
@Component
public class AuraEventBus {

    private static final String MQ_TOPIC_PREFIX = "aura.event.";

    private final ApplicationEventPublisher springPublisher;

    @Autowired(required = false)
    private PluginEventDispatcher pluginEventDispatcher;

    @Autowired(required = false)
    private MqProvider mqProvider;

    @Autowired(required = false)
    private ObjectMapper objectMapper;

    /** Enable MQ forwarding. Off by default — set aura.event.mq-bridge=true to enable. */
    @Value("${aura.event.mq-bridge:false}")
    private boolean mqBridgeEnabled;

    public AuraEventBus(ApplicationEventPublisher springPublisher) {
        this.springPublisher = springPublisher;
    }

    /**
     * Publish event synchronously.
     * Dispatches to Spring listeners and plugin listeners.
     * Exceptions from Spring listeners propagate to the caller.
     * Plugin dispatch errors are logged but do not block the caller.
     */
    public void publish(AuraEvent event) {
        if (SandboxContext.isSandboxMode()) {
            log.debug("Sandbox mode active — suppressing event: {}", event.getClass().getSimpleName());
            return;
        }
        if (event == null) {
            log.warn("Attempted to publish null event, ignoring");
            return;
        }
        log.debug("Publishing event: type={}, id={}, tenant={}",
                event.getEventType(), event.getEventId(), event.getTenantId());

        // Spring listeners: let exceptions propagate so callers know about failures
        springPublisher.publishEvent(event);

        // Plugin dispatch: best-effort, errors logged but not propagated
        dispatchToPlugins(event);

        // MQ bridge: forward to message queue for cross-process consumers
        forwardToMq(event);
    }

    /**
     * Publish event after current transaction commits.
     * Falls back to immediate publish if no active transaction synchronization.
     * Post-commit errors are logged but cannot roll back the already-committed transaction.
     */
    public void publishAfterCommit(AuraEvent event) {
        if (SandboxContext.isSandboxMode()) {
            log.debug("Sandbox mode active — suppressing after-commit event: {}", event.getClass().getSimpleName());
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            publishSafely(event);
                        }
                    });
        } else {
            publish(event);
        }
    }

    /**
     * Safe publish that catches all exceptions — used for post-commit scenarios
     * where the transaction is already committed and cannot be rolled back.
     */
    private void publishSafely(AuraEvent event) {
        try {
            springPublisher.publishEvent(event);
        } catch (Exception e) {
            log.error("Post-commit Spring event dispatch failed for {}: {}",
                    event.getEventType(), e.getMessage(), e);
        }
        dispatchToPlugins(event);
        forwardToMq(event);
    }

    private void dispatchToPlugins(AuraEvent event) {
        if (pluginEventDispatcher != null) {
            try {
                pluginEventDispatcher.dispatch(event);
            } catch (Exception e) {
                log.error("Plugin event dispatch failed for {}: {}",
                        event.getEventType(), e.getMessage(), e);
            }
        }
    }

    /**
     * Forward event to MQ for cross-process distribution.
     * Only active when aura.event.mq-bridge=true AND a non-InMemory MqProvider is configured.
     * Best-effort: errors are logged but never propagate to callers.
     */
    private void forwardToMq(AuraEvent event) {
        if (!mqBridgeEnabled || mqProvider == null || mqProvider instanceof InMemoryMqProvider) {
            return;
        }
        try {
            String topic = MQ_TOPIC_PREFIX + event.getEventType();
            Map<String, String> headers = new HashMap<>();
            headers.put("eventId", event.getEventId());
            headers.put("eventType", event.getEventType());
            if (event.getTenantId() != null) {
                headers.put("tenantId", event.getTenantId().toString());
            }
            if (event.getModelCode() != null) {
                headers.put("modelCode", event.getModelCode());
            }

            String body;
            if (objectMapper != null) {
                Map<String, Object> envelope = new HashMap<>();
                envelope.put("eventId", event.getEventId());
                envelope.put("eventType", event.getEventType());
                envelope.put("tenantId", event.getTenantId());
                envelope.put("modelCode", event.getModelCode());
                envelope.put("recordId", event.getRecordId());
                envelope.put("payload", event.getPayload());
                envelope.put("occurredAt", event.getOccurredAt().toString());
                body = objectMapper.writeValueAsString(envelope);
            } else {
                body = event.getEventType() + ":" + event.getEventId();
            }

            mqProvider.send(topic, body, headers);
            log.debug("Event forwarded to MQ: topic={}, eventId={}", topic, event.getEventId());
        } catch (Exception e) {
            log.warn("MQ forwarding failed for event {}: {}", event.getEventId(), e.getMessage());
        }
    }
}
