package com.auraboot.framework.integration.mapper;

import lombok.Data;

import java.time.Instant;

/** Immutable operator disposition as read from the append-only quarantine audit. */
@Data
public class ReliableIntegrationQuarantineRow {
    private String recordPid;
    private String eventId;
    private String correlationId;
    private int replayCount;
    private String requestedBy;
    private String reason;
    private Instant requestedAt;
}
