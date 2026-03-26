package com.auraboot.framework.automation.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Automation action definition
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AutomationAction {

    /**
     * Action type:
     * - SEND_NOTIFICATION: Send notification to users
     * - UPDATE_RECORD: Update the triggering record or related records
     * - CREATE_RECORD: Create new records
     * - EXECUTE_COMMAND: Execute a defined command
     * - CALL_API: Call external API
     * - SEND_WEBHOOK: Send webhook to external system
     * - DELAY: Wait for specified duration
     * - CONDITION: Conditional branching
     * - LOOP: Loop over collection
     */
    private String type;

    /**
     * Action configuration (type-specific)
     */
    private Map<String, Object> config;

    /**
     * Execution sequence (0-based)
     */
    private Integer sequence;

    /**
     * Action label for display
     */
    private String label;

    /**
     * Whether to continue on error
     */
    private Boolean continueOnError;

    /**
     * Retry configuration
     */
    private RetryConfig retry;

    /**
     * Retry configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RetryConfig {
        /**
         * Maximum retry attempts
         */
        private Integer maxAttempts;

        /**
         * Initial delay in milliseconds
         */
        private Long initialDelayMs;

        /**
         * Backoff multiplier
         */
        private Double backoffMultiplier;
    }
}
