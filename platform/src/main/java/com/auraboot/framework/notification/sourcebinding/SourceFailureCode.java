package com.auraboot.framework.notification.sourcebinding;

/** Bounded diagnostics; arbitrary exception messages never enter a poll result. */
public enum SourceFailureCode {
    BINDING_INVALID, ENVELOPE_CONFLICT, DELIVERY_BLOCKED, TRANSACTION_CONFLICT, PROCESSING_FAILED;
    static SourceFailureCode classify(RuntimeException failure) {
        if (failure instanceof org.springframework.dao.ConcurrencyFailureException) return TRANSACTION_CONFLICT;
        return switch (String.valueOf(failure.getMessage())) {
            case "NOTIFICATION_SOURCE_BINDING_INVALID" -> BINDING_INVALID;
            case "RELIABLE_ENVELOPE_CONFLICT" -> ENVELOPE_CONFLICT;
            case "NOTIFICATION_SOURCE_DELIVERY_BLOCKED" -> DELIVERY_BLOCKED;
            default -> PROCESSING_FAILED;
        };
    }
}
