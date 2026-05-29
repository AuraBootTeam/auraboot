package com.auraboot.framework.dashboard.service.impl;

import com.auraboot.framework.dashboard.dto.WorkbenchStatsDTO;
import com.auraboot.framework.dashboard.dto.WorkbenchStatsDTO.StatItem;
import com.auraboot.framework.dashboard.service.WorkbenchStatsService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the 7-day sparkline series carried by the
 * {@code inbox_pending} StatItem.
 */
class WorkbenchStatsServiceImplSeriesTest extends BaseIntegrationTest {

    @Autowired
    private WorkbenchStatsService service;

    @Test
    @DisplayName("getStats inbox_pending carries a 7-day daily series")
    void getStats_inboxPending_includes7DaySeries() {
        WorkbenchStatsDTO dto = service.getStats(List.of("inbox_pending"));
        StatItem item = dto.getStats().get("inbox_pending");
        assertThat(item).isNotNull();
        assertThat(item.getSeries())
                .as("inbox_pending must have a 7-day series")
                .isNotNull();
        assertThat(item.getSeries().getPeriod()).isEqualTo("day");
        assertThat(item.getSeries().getPoints()).hasSize(7);
        // Each point should be a non-negative number
        item.getSeries().getPoints().forEach(p ->
                assertThat(p.longValue()).isGreaterThanOrEqualTo(0L)
        );
    }

    @Test
    @DisplayName("Other stats either have no series or a 7-point series; never throws")
    void getStats_otherStats_doNotBreak() {
        WorkbenchStatsDTO dto = service.getStats(List.of("bpm_running"));
        StatItem item = dto.getStats().get("bpm_running");
        assertThat(item).isNotNull();
        if (item.getSeries() != null) {
            assertThat(item.getSeries().getPoints()).hasSize(7);
        }
    }
}
