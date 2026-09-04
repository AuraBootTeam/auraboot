package com.auraboot.framework.integration;

import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.common.util.PaginationSafetyUtils;
import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.integration.ReliableIntegrationOperatorContracts.DeadLetterDetail;
import com.auraboot.framework.integration.ReliableIntegrationOperatorContracts.DeadLetterSummary;
import com.auraboot.framework.integration.ReliableIntegrationOperatorContracts.ReceiptView;
import com.auraboot.framework.integration.ReliableIntegrationOperatorContracts.ReplayHistoryView;
import com.auraboot.framework.integration.ReliableIntegrationOperatorContracts.ReplayResult;
import com.auraboot.framework.integration.mapper.ReliableIntegrationOperatorMapper;
import com.auraboot.framework.integration.mapper.ReliableIntegrationOperatorRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/** Tenant-admin application service for payload-free DLQ inspection and attributed replay. */
@Service
@RequiredArgsConstructor
public class ReliableIntegrationOperatorService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> STATUSES = Set.of("open", "replayed");

    private final ReliableIntegrationOperatorMapper mapper;
    private final ReliableIntegrationMetrics metrics;

    @Transactional(readOnly = true)
    public PageResult<DeadLetterSummary> list(long tenantId, String status, String eventType,
                                               String correlationId, int pageNum, int pageSize) {
        String safeStatus = normalizeStatus(status);
        int safePage = PaginationSafetyUtils.pageNumber(pageNum);
        int safeSize = PaginationSafetyUtils.pageSize(pageSize, MAX_PAGE_SIZE);
        int offset = PaginationSafetyUtils.offset(safePage, safeSize, MAX_PAGE_SIZE);
        long total = mapper.count(tenantId, safeStatus, trimToNull(eventType), trimToNull(correlationId));
        List<DeadLetterSummary> records = mapper.list(
                tenantId, safeStatus, trimToNull(eventType), trimToNull(correlationId), safeSize, offset)
                .stream().map(this::summary).toList();
        return new PageResult<>(records, total, (long) safeSize, (long) safePage);
    }

    @Transactional(readOnly = true)
    public DeadLetterDetail detail(long tenantId, String eventId) {
        ReliableIntegrationOperatorRow row = requireRow(tenantId, eventId);
        List<ReceiptView> receipts = mapper.receipts(tenantId, eventId).stream()
                .map(value -> new ReceiptView(value.getConsumerCode(), value.getStatus(),
                        value.getReceivedAt(), value.getAppliedAt()))
                .toList();
        List<ReplayHistoryView> history = mapper.replayHistory(tenantId, eventId).stream()
                .map(value -> new ReplayHistoryView(value.getRecordPid(), value.getAttempt(),
                        value.getRequestedBy(), value.getReason(), value.getCorrelationId(),
                        value.getRequestedAt()))
                .toList();
        return new DeadLetterDetail(summary(row), receipts, history);
    }

    @Transactional
    public ReplayResult replay(long tenantId, String eventId, String requestedBy,
                               String reason, int expectedReplayCount) {
        String safeEventId = requireText(eventId, "eventId is required");
        String safeRequestedBy = requireText(requestedBy, "operator identity is required");
        String safeReason = requireText(reason, "replay reason is required");
        if (safeReason.length() > 512) {
            throw new IllegalArgumentException("replay reason must not exceed 512 characters");
        }
        if (expectedReplayCount < 0) {
            throw new IllegalArgumentException("expectedReplayCount must not be negative");
        }

        ReliableIntegrationOperatorRow current = mapper.find(tenantId, safeEventId);
        if (current == null) {
            throw new NoSuchElementException("dead letter not found");
        }
        Instant requestedAt = Instant.now();
        int changed = mapper.replay(UlidGenerator.generate(), tenantId, safeEventId, safeRequestedBy,
                safeReason, expectedReplayCount, requestedAt);
        if (changed != 1) {
            ReplayResult exactRetry = findExactRetry(
                    tenantId, safeEventId, safeRequestedBy, safeReason, expectedReplayCount);
            if (exactRetry != null) {
                return exactRetry;
            }
            throw new IllegalStateException("dead letter changed or is not open");
        }
        metrics.record("replayed");
        return new ReplayResult(safeEventId, "pending", expectedReplayCount + 1,
                safeRequestedBy, safeReason, current.getCorrelationId(), requestedAt);
    }

    private ReplayResult findExactRetry(long tenantId, String eventId, String requestedBy,
                                        String reason, int expectedReplayCount) {
        return mapper.replayHistory(tenantId, eventId).stream()
                .filter(value -> value.getAttempt() == expectedReplayCount + 1)
                .filter(value -> Objects.equals(value.getRequestedBy(), requestedBy))
                .filter(value -> Objects.equals(value.getReason(), reason))
                .findFirst()
                .map(value -> new ReplayResult(eventId, "pending", value.getAttempt(), requestedBy,
                        reason, value.getCorrelationId(), value.getRequestedAt()))
                .orElse(null);
    }

    private ReliableIntegrationOperatorRow requireRow(long tenantId, String eventId) {
        ReliableIntegrationOperatorRow row = mapper.find(tenantId, requireText(eventId, "eventId is required"));
        if (row == null) {
            throw new NoSuchElementException("dead letter not found");
        }
        return row;
    }

    private DeadLetterSummary summary(ReliableIntegrationOperatorRow row) {
        return new DeadLetterSummary(row.getEventId(), row.getEventType(), row.getEventSource(),
                row.getSubject(), row.getCorrelationId(), row.getStatus(), row.getErrorDetail(),
                row.getFailedAt(), row.getReplayedAt(), row.getReplayedBy(), row.getReplayCount(),
                row.getReceiptCount(), row.getAppliedReceiptCount());
    }

    private String normalizeStatus(String status) {
        String normalized = trimToNull(status);
        if (normalized != null && !STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("status must be open or replayed");
        }
        return normalized;
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
