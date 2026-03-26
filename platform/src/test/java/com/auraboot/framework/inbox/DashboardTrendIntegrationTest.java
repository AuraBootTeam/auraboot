package com.auraboot.framework.inbox;

import com.auraboot.framework.inbox.dto.TrendData;
import com.auraboot.framework.inbox.mapper.InboxItemMapper;
import com.auraboot.framework.inbox.model.InboxItem;
import com.auraboot.framework.inbox.service.InboxService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for dashboard trend calculations.
 * Validates the TrendData computation and date-range-based inbox item counting.
 *
 * @author AuraBoot Team
 */
@Slf4j
@DisplayName("Dashboard Trend Integration Tests (DT-01~DT-07)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DashboardTrendIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private InboxService inboxService;

    @Autowired
    private InboxItemMapper inboxItemMapper;

    private final String runId = "dt-" + System.currentTimeMillis();

    // ==================== DT-01: TrendData.of — positive change ====================

    @Test
    @Order(1)
    @DisplayName("DT-01: TrendData.of computes positive change correctly")
    void dt01_trendDataPositiveChange() {
        TrendData trend = TrendData.of(15, 12);

        assertThat(trend.getCurrent()).isEqualTo(15);
        assertThat(trend.getPrevious()).isEqualTo(12);
        assertThat(trend.getChange()).isEqualTo(25.0);
        assertThat(trend.getDirection()).isEqualTo("up");
    }

    // ==================== DT-02: TrendData.of — negative change ====================

    @Test
    @Order(2)
    @DisplayName("DT-02: TrendData.of computes negative change correctly")
    void dt02_trendDataNegativeChange() {
        TrendData trend = TrendData.of(5, 8);

        assertThat(trend.getCurrent()).isEqualTo(5);
        assertThat(trend.getPrevious()).isEqualTo(8);
        assertThat(trend.getChange()).isEqualTo(-37.5);
        assertThat(trend.getDirection()).isEqualTo("down");
    }

    // ==================== DT-03: TrendData.of — flat (no change) ====================

    @Test
    @Order(3)
    @DisplayName("DT-03: TrendData.of computes flat when current equals previous")
    void dt03_trendDataFlat() {
        TrendData trend = TrendData.of(3, 3);

        assertThat(trend.getCurrent()).isEqualTo(3);
        assertThat(trend.getPrevious()).isEqualTo(3);
        assertThat(trend.getChange()).isEqualTo(0.0);
        assertThat(trend.getDirection()).isEqualTo("flat");
    }

    // ==================== DT-04: TrendData.of — previous is zero ====================

    @Test
    @Order(4)
    @DisplayName("DT-04: TrendData.of uses max(previous, 1) to avoid division by zero")
    void dt04_trendDataPreviousZero() {
        TrendData trend = TrendData.of(7, 0);

        assertThat(trend.getCurrent()).isEqualTo(7);
        assertThat(trend.getPrevious()).isEqualTo(0);
        assertThat(trend.getChange()).isEqualTo(700.0);
        assertThat(trend.getDirection()).isEqualTo("up");
    }

    // ==================== DT-05: TrendData.of — both zero ====================

    @Test
    @Order(5)
    @DisplayName("DT-05: TrendData.of returns flat when both current and previous are zero")
    void dt05_trendDataBothZero() {
        TrendData trend = TrendData.of(0, 0);

        assertThat(trend.getCurrent()).isEqualTo(0);
        assertThat(trend.getPrevious()).isEqualTo(0);
        assertThat(trend.getChange()).isEqualTo(0.0);
        assertThat(trend.getDirection()).isEqualTo("flat");
    }

    // ==================== DT-06: countByTypeInDateRange returns correct count ====================

    @Test
    @Order(6)
    @DisplayName("DT-06: countByTypeInDateRange counts items within the specified range")
    void dt06_countByTypeInDateRange() {
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();

        // Create 3 inbox items
        for (int i = 0; i < 3; i++) {
            InboxItem item = new InboxItem();
            item.setTenantId(tenantId);
            item.setUserId(userId);
            item.setItemType("approval");
            item.setTitle(runId + "-approval-" + i);
            item.setPriority("normal");
            item.setStatus("pending");
            item.setClientItemId(runId + "-approval-" + i);
            inboxService.createItem(item);
        }

        // Count items created in a range that includes now
        Instant start = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant end = Instant.now().plus(1, ChronoUnit.HOURS);
        int count = inboxItemMapper.countByTypeInDateRange(tenantId, userId, "approval", start, end);

        assertThat(count).isGreaterThanOrEqualTo(3);

        // Count items in a range that excludes them (far future)
        Instant futureStart = Instant.now().plus(30, ChronoUnit.DAYS);
        Instant futureEnd = Instant.now().plus(31, ChronoUnit.DAYS);
        int futureCount = inboxItemMapper.countByTypeInDateRange(tenantId, userId, "approval", futureStart, futureEnd);

        assertThat(futureCount).isEqualTo(0);
    }

    // ==================== DT-07: countAllInDateRange returns correct count ====================

    @Test
    @Order(7)
    @DisplayName("DT-07: countAllInDateRange counts all items regardless of type")
    void dt07_countAllInDateRange() {
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();

        // Create items of different types
        for (String type : new String[]{"task", "message"}) {
            InboxItem item = new InboxItem();
            item.setTenantId(tenantId);
            item.setUserId(userId);
            item.setItemType(type);
            item.setTitle(runId + "-" + type + "-all");
            item.setPriority("normal");
            item.setStatus("pending");
            item.setClientItemId(runId + "-" + type + "-all");
            inboxService.createItem(item);
        }

        Instant start = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant end = Instant.now().plus(1, ChronoUnit.HOURS);
        int count = inboxItemMapper.countAllInDateRange(tenantId, userId, start, end);

        // Should include the 3 approvals from DT-06 + 2 from DT-07 = at least 5
        assertThat(count).isGreaterThanOrEqualTo(5);

        // Verify type-specific count excludes other types
        int taskCount = inboxItemMapper.countByTypeInDateRange(tenantId, userId, "task", start, end);
        int messageCount = inboxItemMapper.countByTypeInDateRange(tenantId, userId, "message", start, end);

        assertThat(taskCount).isGreaterThanOrEqualTo(1);
        assertThat(messageCount).isGreaterThanOrEqualTo(1);
        assertThat(count).isGreaterThanOrEqualTo(taskCount + messageCount);
    }
}
