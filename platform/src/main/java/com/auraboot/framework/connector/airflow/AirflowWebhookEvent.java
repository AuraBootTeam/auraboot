package com.auraboot.framework.connector.airflow;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Value;
import org.springframework.context.ApplicationEvent;

/**
 * Spring application event raised after a successfully verified Airflow
 * webhook lands. Downstream listeners ({@link
 * org.springframework.context.event.EventListener @EventListener}) react
 * without {@link AirflowWebhookController} knowing them — keeps the
 * dispatcher decoupled from sync run / metrics writers / IM notifiers.
 *
 * <p>PRD 18-C §C.3.3. {@link #payload} carries the raw JSON node from the
 * webhook body so listeners can read connector-specific fields without a
 * schema migration here.
 */
@Value
@Builder
public class AirflowWebhookEvent {

    String webhookId;
    String event;
    String dagId;
    String taskId;
    String executionDate;
    String status;
    JsonNode payload;

    /** Convenience wrapper so the dispatcher does not double-wrap. */
    public static class Spring extends ApplicationEvent {
        public final AirflowWebhookEvent event;
        public Spring(Object source, AirflowWebhookEvent event) {
            super(source);
            this.event = event;
        }
    }
}
