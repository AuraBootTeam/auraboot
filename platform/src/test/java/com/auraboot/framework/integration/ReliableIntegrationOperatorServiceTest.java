package com.auraboot.framework.integration;

import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.integration.ReliableIntegrationOperatorContracts.DeadLetterDetail;
import com.auraboot.framework.integration.ReliableIntegrationOperatorContracts.DeadLetterSummary;
import com.auraboot.framework.integration.ReliableIntegrationOperatorContracts.ReplayResult;
import com.auraboot.framework.integration.mapper.ReliableIntegrationOperatorMapper;
import com.auraboot.framework.integration.mapper.ReliableIntegrationOperatorRow;
import com.auraboot.framework.integration.mapper.ReliableIntegrationReceiptRow;
import com.auraboot.framework.integration.mapper.ReliableIntegrationReplayHistoryRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReliableIntegrationOperatorServiceTest {

    private static final long TENANT_ID = 42L;

    @Mock private ReliableIntegrationOperatorMapper mapper;
    @Mock private ReliableIntegrationMetrics metrics;

    private ReliableIntegrationOperatorService service;

    @BeforeEach
    void setUp() {
        service = new ReliableIntegrationOperatorService(mapper, metrics);
    }

    @Test
    void listBoundsPaginationAndMapsOnlyPublicProjection() {
        ReliableIntegrationOperatorRow row = row("evt-1", "open", 0);
        when(mapper.count(TENANT_ID, "open", null, null)).thenReturn(1L);
        when(mapper.list(TENANT_ID, "open", null, null, 100, 0)).thenReturn(List.of(row));

        PageResult<DeadLetterSummary> result = service.list(TENANT_ID, " open ", " ", null, 0, 500);

        assertThat(result.getCurrent()).isEqualTo(1L);
        assertThat(result.getSize()).isEqualTo(100L);
        assertThat(result.getRecords()).extracting(DeadLetterSummary::eventId).containsExactly("evt-1");
    }

    @Test
    void detailDoesNotRevealMissingOrCrossTenantEvent() {
        when(mapper.find(TENANT_ID, "evt-other")).thenReturn(null);

        assertThatThrownBy(() -> service.detail(TENANT_ID, "evt-other"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage("dead letter not found");
    }

    @Test
    void detailIncludesReceiptsAndAppendOnlyReplayHistory() {
        ReliableIntegrationOperatorRow row = row("evt-1", "replayed", 1);
        ReliableIntegrationReceiptRow receipt = new ReliableIntegrationReceiptRow();
        receipt.setConsumerCode("inventory-v1");
        receipt.setStatus("applied");
        receipt.setReceivedAt(Instant.EPOCH);
        receipt.setAppliedAt(Instant.EPOCH.plusSeconds(1));
        ReliableIntegrationReplayHistoryRow history = new ReliableIntegrationReplayHistoryRow();
        history.setRecordPid("01M1REPLAY0000000000000000");
        history.setAttempt(1);
        history.setRequestedBy("01M1OPERATOR00000000000000");
        history.setReason("consumer deployed");
        history.setCorrelationId("corr-1");
        history.setRequestedAt(Instant.EPOCH.plusSeconds(2));
        when(mapper.find(TENANT_ID, "evt-1")).thenReturn(row);
        when(mapper.receipts(TENANT_ID, "evt-1")).thenReturn(List.of(receipt));
        when(mapper.replayHistory(TENANT_ID, "evt-1")).thenReturn(List.of(history));

        DeadLetterDetail result = service.detail(TENANT_ID, "evt-1");

        assertThat(result.receipts()).singleElement().satisfies(value ->
                assertThat(value.consumerCode()).isEqualTo("inventory-v1"));
        assertThat(result.replayHistory()).singleElement().satisfies(value -> {
            assertThat(value.attempt()).isEqualTo(1);
            assertThat(value.reason()).isEqualTo("consumer deployed");
        });
    }

    @Test
    void replayUsesCasAndAttributesActorReasonAndCorrelation() {
        when(mapper.find(TENANT_ID, "evt-1")).thenReturn(row("evt-1", "open", 2));
        when(mapper.replay(anyString(), eq(TENANT_ID), eq("evt-1"), eq("operator-pid"),
                eq("consumer deployed"), eq(2), any(Instant.class))).thenReturn(1);

        ReplayResult result = service.replay(
                TENANT_ID, "evt-1", "operator-pid", "consumer deployed", 2);

        assertThat(result.status()).isEqualTo("pending");
        assertThat(result.replayCount()).isEqualTo(3);
        assertThat(result.correlationId()).isEqualTo("corr-1");
        ArgumentCaptor<String> recordPid = ArgumentCaptor.forClass(String.class);
        verify(mapper).replay(recordPid.capture(), eq(TENANT_ID), eq("evt-1"), eq("operator-pid"),
                eq("consumer deployed"), eq(2), any(Instant.class));
        assertThat(recordPid.getValue()).hasSize(26);
        verify(metrics).record("replayed");
    }

    @Test
    void replayRejectsStaleCasWithoutRecordingSuccessMetric() {
        when(mapper.find(TENANT_ID, "evt-1")).thenReturn(row("evt-1", "open", 2));
        when(mapper.replay(anyString(), eq(TENANT_ID), eq("evt-1"), eq("operator-pid"),
                eq("retry"), eq(1), any(Instant.class))).thenReturn(0);
        when(mapper.replayHistory(TENANT_ID, "evt-1")).thenReturn(List.of());

        assertThatThrownBy(() -> service.replay(TENANT_ID, "evt-1", "operator-pid", "retry", 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("dead letter changed or is not open");
        verifyNoInteractions(metrics);
    }

    @Test
    void replayReturnsOriginalResultForExactRetryWithoutRecordingAnotherMetric() {
        when(mapper.find(TENANT_ID, "evt-1")).thenReturn(row("evt-1", "replayed", 3));
        when(mapper.replay(anyString(), eq(TENANT_ID), eq("evt-1"), eq("operator-pid"),
                eq("consumer deployed"), eq(2), any(Instant.class))).thenReturn(0);
        ReliableIntegrationReplayHistoryRow history = new ReliableIntegrationReplayHistoryRow();
        history.setAttempt(3);
        history.setRequestedBy("operator-pid");
        history.setReason("consumer deployed");
        history.setCorrelationId("corr-1");
        history.setRequestedAt(Instant.EPOCH.plusSeconds(2));
        when(mapper.replayHistory(TENANT_ID, "evt-1")).thenReturn(List.of(history));

        ReplayResult result = service.replay(
                TENANT_ID, "evt-1", "operator-pid", "consumer deployed", 2);

        assertThat(result.status()).isEqualTo("pending");
        assertThat(result.replayCount()).isEqualTo(3);
        assertThat(result.requestedAt()).isEqualTo(Instant.EPOCH.plusSeconds(2));
        verifyNoInteractions(metrics);
    }

    private ReliableIntegrationOperatorRow row(String eventId, String status, int replayCount) {
        ReliableIntegrationOperatorRow row = new ReliableIntegrationOperatorRow();
        row.setEventId(eventId);
        row.setEventType("inventory.issued.v1");
        row.setEventSource("inventory");
        row.setSubject("work-order/WO-1");
        row.setCorrelationId("corr-1");
        row.setStatus(status);
        row.setErrorDetail("consumer unavailable");
        row.setFailedAt(Instant.EPOCH);
        row.setReplayCount(replayCount);
        return row;
    }
}
