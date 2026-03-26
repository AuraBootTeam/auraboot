package com.auraboot.framework.bpm.dto;

import java.util.Map;

/**
 * Configuration for a process trigger.
 */
public record TriggerConfig(
        /** Cron expression (for SCHEDULED type) */
        String cronExpression,

        /** Event type to listen for (for EVENT type) */
        String eventType,

        /** Webhook secret (for WEBHOOK type) */
        String webhookSecret,

        /** Default payload to merge when trigger fires */
        Map<String, Object> defaultPayload
) {}
