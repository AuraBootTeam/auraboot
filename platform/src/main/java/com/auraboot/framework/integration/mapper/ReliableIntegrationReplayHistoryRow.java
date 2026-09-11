package com.auraboot.framework.integration.mapper;

import lombok.Data;

import java.time.Instant;

@Data
public class ReliableIntegrationReplayHistoryRow {
    private String recordPid;
    private int attempt;
    private String requestedBy;
    private String reason;
    private String correlationId;
    private Instant requestedAt;
}
