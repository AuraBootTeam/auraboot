package com.auraboot.framework.integration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** Public, payload-free contracts for tenant administrators operating reliable integration failures. */
public final class ReliableIntegrationOperatorContracts {

    private ReliableIntegrationOperatorContracts() {
    }

    public record DeadLetterSummary(
            String eventId,
            String eventType,
            String eventSource,
            String subject,
            String correlationId,
            String status,
            String errorDetail,
            Instant failedAt,
            Instant replayedAt,
            String replayedBy,
            int replayCount,
            int receiptCount,
            int appliedReceiptCount) {
    }

    public record ReceiptView(
            String consumerCode,
            String status,
            Instant receivedAt,
            Instant appliedAt) {
    }

    public record ReplayHistoryView(
            String recordPid,
            int attempt,
            String requestedBy,
            String reason,
            String correlationId,
            Instant requestedAt) {
    }

    public record DeadLetterDetail(
            DeadLetterSummary deadLetter,
            List<ReceiptView> receipts,
            List<ReplayHistoryView> replayHistory) {
    }

    public record ReplayRequest(
            @NotBlank @Size(max = 512) String reason,
            @NotNull @PositiveOrZero Integer expectedReplayCount) {
    }

    public record ReplayResult(
            String eventId,
            String status,
            int replayCount,
            String requestedBy,
            String reason,
            String correlationId,
            Instant requestedAt) {
    }
}
