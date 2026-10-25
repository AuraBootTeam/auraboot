package com.auraboot.framework.meta.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.auraboot.framework.exception.RequestIntentConflictException;
import com.auraboot.framework.meta.entity.IdempotencyRecord;
import com.auraboot.framework.meta.mapper.IdempotencyRecordMapper;
import com.auraboot.framework.meta.service.IdempotencyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class IdempotencyRetainedClaimTest {
    private final IdempotencyRecordMapper mapper = mock(IdempotencyRecordMapper.class);
    private final IdempotencyServiceImpl service = new IdempotencyServiceImpl(mapper, new ObjectMapper());

    @AfterEach void clearTransactionMarker() { TransactionSynchronizationManager.clear(); }

    @Test void retainedOwnerUsesExplicitInsertAndNeverReclaimsFiniteRows() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(mapper.insertRetainedIdempotent(any())).thenReturn(1);
        assertThat(service.claimRetainedScopedIdempotency(" key ", " cart:add:v1 ", Map.of("quantity", 2), 7L)).isNull();
        var captor = ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(mapper).insertRetainedIdempotent(captor.capture());
        assertThat(captor.getValue().getClientRequestId()).isEqualTo("cart:add:v1:key");
        assertThat(captor.getValue().getExpiresAt()).isNull();
        assertThat(captor.getValue().getStatus()).isEqualTo("processing");
        verifyNoMoreInteractions(mapper);
    }

    @Test void retainedReplayMatchesCanonicalIntentAndRejectsChangedPayload() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(mapper.insertRetainedIdempotent(any())).thenReturn(1, 0, 0);
        service.claimRetainedScopedIdempotency("key", "retained", Map.of("a", 1, "b", 2), 7L);
        var captor = ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(mapper).insertRetainedIdempotent(captor.capture());
        var row = captor.getValue(); row.setStatus("completed"); row.setOutcome("{\"pid\":\"original\"}");
        when(mapper.findRetainedByClientRequestId(7L,"retained:key")).thenReturn(row);
        assertThat(service.claimRetainedScopedIdempotency("key", "retained", Map.of("b", 2, "a", 1), 7L)).containsEntry("pid", "original");
        assertThatThrownBy(() -> service.claimRetainedScopedIdempotency("key", "retained", Map.of("a", 2), 7L)).isInstanceOf(RequestIntentConflictException.class);
        verify(mapper, never()).deleteExpiredByClientRequestId(any(),any());
    }

    @Test void retainedClaimRequiresIdentityAndAnActualTransactionBeforeMapperIo() {
        assertThatThrownBy(() -> service.claimRetainedScopedIdempotency("", "operation", Map.of(), 7L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.claimRetainedScopedIdempotency("key", null, Map.of(), 7L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.claimRetainedScopedIdempotency("key", "operation", Map.of(), 7L)).isInstanceOf(IllegalStateException.class);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> service.claimRetainedScopedIdempotency("key", "operation", Map.of(), null)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(mapper);
    }

    @Test void existingFiniteClaimKeepsItsTwentyFourHourExpiry() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(mapper.insertIdempotent(any())).thenReturn(1);
        Instant before = Instant.now();
        assertThat(service.claimScopedIdempotency("key", "finite", Map.of(), 7L)).isNull();
        var captor = ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(mapper).insertIdempotent(captor.capture());
        assertThat(captor.getValue().getExpiresAt()).isAfter(before.plusSeconds(86399)).isBefore(Instant.now().plusSeconds(86401));
        verify(mapper).deleteExpiredByClientRequestId(7L,"finite:key");
        verify(mapper,never()).insertRetainedIdempotent(any());
    }

    @Test void olderProvidersFailClosedInsteadOfDowngradingRetention() {
        IdempotencyService oldProvider = mock(IdempotencyService.class, CALLS_REAL_METHODS);
        assertThatThrownBy(() -> oldProvider.claimRetainedScopedIdempotency("key", "operation", Map.of(), 7L)).isInstanceOf(UnsupportedOperationException.class);
        verify(oldProvider,never()).claimScopedIdempotency(any(),any(),any(),any());
    }
}
