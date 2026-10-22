package com.auraboot.framework.dashboard.service;

import com.auraboot.framework.dashboard.dto.WorkbenchStatsDTO;
import com.auraboot.framework.dashboard.dto.WorkbenchStatsDTO.StatItem;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for WorkbenchStatsService.
 * Tests real database queries against PostgreSQL.
 */
class WorkbenchStatsServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private WorkbenchStatsService workbenchStatsService;

    @Test
    @DisplayName("getStats with null keys returns all default keys with correct structure")
    void getStats_nullKeys_returnsAllDefaultKeys() {
        WorkbenchStatsDTO result = workbenchStatsService.getStats(null);

        assertThat(result).isNotNull();
        assertThat(result.getStats()).isNotNull();

        Map<String, StatItem> stats = result.getStats();

        assertThat(stats).containsOnlyKeys("inbox_pending", "inbox_urgent");

        // Verify inbox_pending has correct format and label
        StatItem inboxPending = stats.get("inbox_pending");
        assertThat(inboxPending).isNotNull();
        assertThat(inboxPending.getFormat()).isEqualTo("number");
        assertThat(inboxPending.getLabel()).isEqualTo("workbench.stats.inbox_pending");
        assertThat(inboxPending.getValue()).isNotNull();

        // Verify inbox_urgent has correct structure
        StatItem inboxUrgent = stats.get("inbox_urgent");
        assertThat(inboxUrgent).isNotNull();
        assertThat(inboxUrgent.getFormat()).isEqualTo("number");
        assertThat(inboxUrgent.getLabel()).isEqualTo("workbench.stats.inbox_urgent");

    }

    @Test
    @DisplayName("getStats with empty keys returns all default keys")
    void getStats_emptyKeys_returnsAllDefaultKeys() {
        WorkbenchStatsDTO result = workbenchStatsService.getStats(List.of());

        assertThat(result).isNotNull();
        assertThat(result.getStats()).hasSize(2);
    }

    @Test
    @DisplayName("getStats with specific keys returns only requested stats")
    void getStats_specificKeys_returnsOnlyRequested() {
        List<String> keys = List.of("inbox_pending", "inbox_urgent");

        WorkbenchStatsDTO result = workbenchStatsService.getStats(keys);

        assertThat(result).isNotNull();
        assertThat(result.getStats()).hasSize(2);
        assertThat(result.getStats()).containsOnlyKeys("inbox_pending", "inbox_urgent");

        // Verify both items have proper structure
        StatItem pending = result.getStats().get("inbox_pending");
        assertThat(pending.getValue()).isNotNull();
        assertThat(pending.getFormat()).isEqualTo("number");
        assertThat(pending.getLabel()).isEqualTo("workbench.stats.inbox_pending");

        StatItem urgent = result.getStats().get("inbox_urgent");
        assertThat(urgent.getValue()).isNotNull();
        assertThat(urgent.getFormat()).isEqualTo("number");
    }

    @Test
    @DisplayName("getStats with unknown key ignores it gracefully")
    void getStats_unknownKey_ignored() {
        List<String> keys = List.of("inbox_pending", "nonexistent_key");

        WorkbenchStatsDTO result = workbenchStatsService.getStats(keys);

        assertThat(result).isNotNull();
        // Only the valid key should be present
        assertThat(result.getStats()).hasSize(1);
        assertThat(result.getStats()).containsKey("inbox_pending");
        assertThat(result.getStats()).doesNotContainKey("nonexistent_key");
    }

    @Test
    @DisplayName("getStats with single key returns correct stat")
    void getStats_singleKey_returnsCorrectStat() {
        WorkbenchStatsDTO result = workbenchStatsService.getStats(List.of("inbox_pending"));

        assertThat(result).isNotNull();
        assertThat(result.getStats()).hasSize(1);

        StatItem item = result.getStats().get("inbox_pending");
        assertThat(item).isNotNull();
        assertThat(item.getLabel()).isEqualTo("workbench.stats.inbox_pending");
        assertThat(item.getFormat()).isEqualTo("number");
        assertThat(item.getValue()).isNotNull();
    }
}
