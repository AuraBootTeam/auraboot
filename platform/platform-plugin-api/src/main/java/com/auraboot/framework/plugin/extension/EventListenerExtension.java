package com.auraboot.framework.plugin.extension;

import org.pf4j.ExtensionPoint;

import java.util.Map;
import java.util.Set;

/**
 * Extension point for event listeners.
 * Plugins can implement this interface to subscribe to platform events.
 *
 * Example usage:
 * <pre>
 * {@code
 * @Extension
 * public class OrderEventListener implements EventListenerExtension {
 *     @Override
 *     public Set<String> getSubscribedEvents() {
 *         return Set.of("order:created", "order:completed");
 *     }
 *
 *     @Override
 *     public void onEvent(EventContext context) {
 *         // Handle event
 *     }
 * }
 * }
 * </pre>
 */
public interface EventListenerExtension extends ExtensionPoint {

    /**
     * Get the set of event types this listener subscribes to.
     * Format: "domain:event" (e.g., "order:created", "user:login")
     *
     * Supports wildcards:
     * - "order:*" - all order events
     * - "*:created" - all created events
     * - "*" - all events
     *
     * @return set of event type patterns
     */
    Set<String> getSubscribedEvents();

    /**
     * Handle an event.
     *
     * @param context event context containing event data
     */
    void onEvent(EventContext context);

    /**
     * Check if this listener should receive the given event.
     *
     * @param eventType the event type to check
     * @return true if this listener is interested in the event
     */
    default boolean isInterestedIn(String eventType) {
        return getSubscribedEvents().stream()
                .anyMatch(pattern -> matchesPattern(pattern, eventType));
    }

    /**
     * Get the execution order of this listener.
     * Lower values execute first.
     * Default is 100.
     *
     * @return execution order
     */
    default int getOrder() {
        return 100;
    }

    /**
     * Whether this listener should run asynchronously.
     * Default is false.
     *
     * @return true if async execution is preferred
     */
    default boolean isAsync() {
        return false;
    }

    /**
     * Check if a pattern matches an event type.
     */
    private static boolean matchesPattern(String pattern, String eventType) {
        if ("*".equals(pattern)) {
            return true;
        }
        if (pattern.endsWith(":*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return eventType.startsWith(prefix);
        }
        if (pattern.startsWith("*:")) {
            String suffix = pattern.substring(1);
            return eventType.endsWith(suffix);
        }
        return pattern.equals(eventType);
    }

    /**
     * Event context containing event data.
     */
    record EventContext(
            Long tenantId,
            String pluginId,
            String namespace,
            String eventType,
            String sourceModel,
            String recordId,
            Map<String, Object> eventData,
            Map<String, Object> previousData,
            long timestamp
    ) {
        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private Long tenantId;
            private String pluginId;
            private String namespace;
            private String eventType;
            private String sourceModel;
            private String recordId;
            private Map<String, Object> eventData;
            private Map<String, Object> previousData;
            private long timestamp = System.currentTimeMillis();

            public Builder tenantId(Long tenantId) {
                this.tenantId = tenantId;
                return this;
            }

            public Builder pluginId(String pluginId) {
                this.pluginId = pluginId;
                return this;
            }

            public Builder namespace(String namespace) {
                this.namespace = namespace;
                return this;
            }

            public Builder eventType(String eventType) {
                this.eventType = eventType;
                return this;
            }

            public Builder sourceModel(String sourceModel) {
                this.sourceModel = sourceModel;
                return this;
            }

            public Builder recordId(String recordId) {
                this.recordId = recordId;
                return this;
            }

            public Builder eventData(Map<String, Object> eventData) {
                this.eventData = eventData;
                return this;
            }

            public Builder previousData(Map<String, Object> previousData) {
                this.previousData = previousData;
                return this;
            }

            public Builder timestamp(long timestamp) {
                this.timestamp = timestamp;
                return this;
            }

            public EventContext build() {
                return new EventContext(tenantId, pluginId, namespace, eventType, sourceModel, recordId, eventData, previousData, timestamp);
            }
        }
    }
}
