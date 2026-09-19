package com.auraboot.framework.dashboard.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.dashboard.dto.WorkbenchStatsDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkbenchStatsServiceImplTest {
    @Mock private JdbcTemplate jdbcTemplate;
    private WorkbenchStatsServiceImpl service;

    @BeforeEach void setUp() {
        service = new WorkbenchStatsServiceImpl(jdbcTemplate);
        MetaContext.setContext(10L, 1L, "u-1", "user");
    }

    @AfterEach void tearDown() { MetaContext.clear(); }

    @Test void defaultsContainOnlyCoreInboxStatistics() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(), any(), any())).thenReturn(3L);
        WorkbenchStatsDTO result = service.getStats(null);
        assertThat(result.getStats()).containsOnlyKeys("inbox_pending", "inbox_urgent");
    }

    @Test void requestedInboxCountIsReturned() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(), any(), any())).thenReturn(7L);
        assertThat(service.getStats(List.of("inbox_pending")).getStats().get("inbox_pending").getValue())
                .isEqualTo(7L);
    }

    @Test void unknownProductOrCustomKeyIsNotFabricatedByCore() {
        assertThat(service.getStats(List.of("product_specific_stat")).getStats()).isEmpty();
    }

    @Test void optionalSeriesFailsSoft() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(), any(), any())).thenReturn(1L);
        when(jdbcTemplate.queryForList(anyString(), eq(Long.class), any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("unavailable"));
        assertThat(service.getStats(List.of("inbox_pending")).getStats().get("inbox_pending").getSeries())
                .isNull();
    }
}
