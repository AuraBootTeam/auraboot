package com.auraboot.framework.integration.mapper;

import lombok.Data;

import java.time.Instant;

@Data
public class ReliableIntegrationReceiptRow {
    private String consumerCode;
    private String status;
    private Instant receivedAt;
    private Instant appliedAt;
}
