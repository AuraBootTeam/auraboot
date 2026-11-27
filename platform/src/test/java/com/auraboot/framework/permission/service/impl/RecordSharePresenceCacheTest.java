package com.auraboot.framework.permission.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.auraboot.framework.permission.mapper.RecordShareMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The presence memo collapses repeated necessary-condition EXISTS probes for the same
 * (tenant, resource, record pid) to a single query, and a share mutation evicts the key so
 * a fresh grant is visible immediately on the pid path. Only the fast reject is memoized —
 * per-subject authorization stays uncached, so revocation never waits on this cache.
 */
class RecordSharePresenceCacheTest {

    @Test
    void repeatedProbesForTheSameRecordHitTheMapperOnce() {
        RecordShareMapper mapper = mock(RecordShareMapper.class);
        when(mapper.hasActiveSharesByRecordPid(anyLong(), anyString(), anyString(), any(Instant.class)))
                .thenReturn(false);
        var cache = new RecordSharePresenceCache(mapper);

        assertThat(cache.hasActiveShares(7L, "orb_plan", "PID1", Instant.now())).isFalse();
        assertThat(cache.hasActiveShares(7L, "orb_plan", "PID1", Instant.now())).isFalse();
        assertThat(cache.hasActiveShares(7L, "orb_plan", "PID1", Instant.now())).isFalse();
        verify(mapper, times(1)).hasActiveSharesByRecordPid(anyLong(), anyString(), anyString(), any(Instant.class));
    }

    @Test
    void differentRecordsAndTenantsDoNotShareAnEntry() {
        RecordShareMapper mapper = mock(RecordShareMapper.class);
        when(mapper.hasActiveSharesByRecordPid(anyLong(), anyString(), anyString(), any(Instant.class)))
                .thenReturn(false);
        var cache = new RecordSharePresenceCache(mapper);

        cache.hasActiveShares(7L, "orb_plan", "PID1", Instant.now());
        cache.hasActiveShares(7L, "orb_plan", "PID2", Instant.now());
        cache.hasActiveShares(8L, "orb_plan", "PID1", Instant.now());
        cache.hasActiveShares(7L, "orb_other", "PID1", Instant.now());
        verify(mapper, times(4)).hasActiveSharesByRecordPid(anyLong(), anyString(), anyString(), any(Instant.class));
    }

    @Test
    void evictMakesTheNextProbeQueryAgain() {
        RecordShareMapper mapper = mock(RecordShareMapper.class);
        when(mapper.hasActiveSharesByRecordPid(anyLong(), anyString(), anyString(), any(Instant.class)))
                .thenReturn(false, true);
        var cache = new RecordSharePresenceCache(mapper);

        assertThat(cache.hasActiveShares(7L, "orb_plan", "PID1", Instant.now())).isFalse();
        cache.evictRecord(7L, "orb_plan", "PID1");
        assertThat(cache.hasActiveShares(7L, "orb_plan", "PID1", Instant.now())).isTrue();
        verify(mapper, times(2)).hasActiveSharesByRecordPid(anyLong(), anyString(), anyString(), any(Instant.class));
    }
}
