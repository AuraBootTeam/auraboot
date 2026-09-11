package com.auraboot.framework.integration.mapper;

import lombok.Data;

import java.time.Instant;

@Data
public class ReliableIntegrationOperatorRow {
    private String eventId;
    private String eventType;
    private String eventSource;
    private String subject;
    private String correlationId;
    private String status;
    private String errorDetail;
    private Instant failedAt;
    private Instant replayedAt;
    private String replayedBy;
    private int replayCount;
    private int receiptCount;
    private int appliedReceiptCount;
}
