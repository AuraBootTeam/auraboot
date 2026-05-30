package com.auraboot.framework.connector.airflow;

/**
 * Webhook-specific failure with a fixed (httpStatus, errorCode) pair so the
 * controller can map directly without a status-from-message heuristic. PRD
 * 18-C §C.3.3 table.
 */
public class AirflowWebhookException extends RuntimeException {

    private final int httpStatus;
    private final String errorCode;

    public AirflowWebhookException(int httpStatus, String errorCode, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
    }

    public int httpStatus() { return httpStatus; }
    public String errorCode() { return errorCode; }
}
