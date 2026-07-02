package com.auraboot.framework.billing.quota.service;

import com.auraboot.framework.billing.quota.mapper.QuotaBucketMapper;
import com.auraboot.framework.billing.quota.model.QuotaBucket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the quota-reserve concurrency guard
 * ({@link QuotaServiceImpl#applyReserveWithRetry}).
 *
 * <p>Security regression (SD9-F1): the reserve CAS carried only a version predicate, so two
 * concurrent authorize calls that both passed the service-layer pre-check could over-reserve
 * past the bucket total (TOCTOU). The fix adds an atomic balance predicate to
 * {@code casAddReserved} and fails fast (rolling back the @Transactional authorize) when a
 * fresh re-read shows a genuine shortfall instead of burning every retry.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Quota reserve concurrency guard")
class QuotaReserveConcurrencyGuardTest {

    @Mock
    private QuotaBucketMapper quotaBucketMapper;

    @InjectMocks
    private QuotaServiceImpl service;

    private QuotaBucket bucket(long id, String total, String used, String reserved, long version) {
        return QuotaBucket.builder()
                .id(id)
                .totalAmount(new BigDecimal(total))
                .usedAmount(new BigDecimal(used))
                .reservedAmount(new BigDecimal(reserved))
                .version(version)
                .build();
    }

    @Test
    @DisplayName("insufficient headroom fails fast without attempting the CAS")
    void insufficientHeadroom_throws() {
        QuotaBucket b = bucket(1L, "100", "0", "80", 0L); // available = 20
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.applyReserveWithRetry(b, new BigDecimal("30")));
        assertTrue(ex.getMessage().contains("Insufficient"));
        verify(quotaBucketMapper, never()).casAddReserved(anyLong(), any(), anyLong());
    }

    @Test
    @DisplayName("sufficient headroom reserves in one CAS")
    void sufficient_reservesOnce() {
        QuotaBucket b = bucket(1L, "100", "0", "0", 0L); // available = 100
        when(quotaBucketMapper.casAddReserved(1L, new BigDecimal("30"), 0L)).thenReturn(1);
        service.applyReserveWithRetry(b, new BigDecimal("30"));
        verify(quotaBucketMapper, times(1)).casAddReserved(1L, new BigDecimal("30"), 0L);
    }

    @Test
    @DisplayName("version conflict re-reads and retries, then succeeds")
    void versionConflict_retriesThenSucceeds() {
        QuotaBucket stale = bucket(1L, "100", "0", "0", 0L);  // available = 100, v0
        QuotaBucket fresh = bucket(1L, "100", "0", "10", 1L); // available = 90, v1 (concurrent reserve of 10)
        when(quotaBucketMapper.casAddReserved(1L, new BigDecimal("30"), 0L)).thenReturn(0); // version moved
        when(quotaBucketMapper.selectById(1L)).thenReturn(fresh);
        when(quotaBucketMapper.casAddReserved(1L, new BigDecimal("30"), 1L)).thenReturn(1);
        service.applyReserveWithRetry(stale, new BigDecimal("30"));
        verify(quotaBucketMapper, times(2)).casAddReserved(eq(1L), any(), anyLong());
    }

    @Test
    @DisplayName("losing the headroom to a concurrent reserver fails fast (no over-reserve)")
    void raceLostHeadroom_throws() {
        QuotaBucket stale = bucket(1L, "100", "0", "0", 0L);  // available = 100, v0
        QuotaBucket fresh = bucket(1L, "100", "0", "90", 1L); // available = 10, v1 (concurrent reserve of 90)
        when(quotaBucketMapper.casAddReserved(1L, new BigDecimal("30"), 0L)).thenReturn(0);
        when(quotaBucketMapper.selectById(1L)).thenReturn(fresh);
        assertThrows(IllegalStateException.class,
                () -> service.applyReserveWithRetry(stale, new BigDecimal("30")));
    }
}
