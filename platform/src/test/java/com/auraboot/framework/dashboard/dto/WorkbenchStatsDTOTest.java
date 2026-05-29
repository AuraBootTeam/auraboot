package com.auraboot.framework.dashboard.dto;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class WorkbenchStatsDTOTest {

    @Test
    void statItem_carriesOptionalSeries() {
        WorkbenchStatsDTO.Series series = WorkbenchStatsDTO.Series.builder()
                .period("day")
                .points(List.of(220, 225, 223, 232, 235, 240, 241))
                .build();

        WorkbenchStatsDTO.StatItem item = WorkbenchStatsDTO.StatItem.builder()
                .value(241)
                .label("workbench.stats.inbox_pending")
                .series(series)
                .build();

        assertThat(item.getSeries()).isNotNull();
        assertThat(item.getSeries().getPeriod()).isEqualTo("day");
        assertThat(item.getSeries().getPoints()).hasSize(7);
        assertThat(item.getSeries().getPoints().get(6)).isEqualTo(241);
    }

    @Test
    void statItem_seriesNullable() {
        WorkbenchStatsDTO.StatItem item = WorkbenchStatsDTO.StatItem.builder()
                .value(0)
                .label("workbench.stats.bpm_running")
                .build();

        assertThat(item.getSeries()).isNull();
    }
}
