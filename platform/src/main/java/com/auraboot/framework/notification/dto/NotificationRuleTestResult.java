package com.auraboot.framework.notification.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Result returned when test-evaluating a notification rule.
 *
 * @since 5.2.0
 */
@Data
@Builder
public class NotificationRuleTestResult {

    /** Whether the rule evaluated successfully (no errors). */
    private boolean success;

    /** Number of records matching the rule's condition filter. */
    private int matchedCount;

    /** Snippet of matched record data (up to 5 records). */
    private List<Object> sampleRecords;

    /** Human-readable summary. */
    private String summary;

    /** Error message if evaluation failed. */
    private String error;
}
