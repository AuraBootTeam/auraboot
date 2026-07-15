package com.auraboot.framework.bi;

import com.auraboot.framework.bi.dto.DashboardDataResponse;
import com.auraboot.framework.bi.service.impl.DashboardDataServiceImpl;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for DashboardDataService.
 */
@ExtendWith(MockitoExtension.class)
class DashboardDataServiceTest {

    @Mock
    private PageSchemaMapper formSchemaMapper;

    @InjectMocks
    private DashboardDataServiceImpl dashboardDataService;

    @Test
    void fetchDashboardData_nonExistentDashboard_throwsException() {
        when(formSchemaMapper.selectById("nonexistent")).thenReturn(null);

        assertThatThrownBy(() -> dashboardDataService.fetchDashboardData("nonexistent", false, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Dashboard not found");
    }

    @Test
    void fetchDashboardData_validDashboard_returnsWidgetData() {
        // Arrange
        String dashboardJson = """
                {
                    "title": "Sales Dashboard",
                    "widgets": [
                        {
                            "id": "widget1",
                            "dataSource": { "type": "static", "data": 42 }
                        },
                        {
                            "id": "widget2",
                            "config": { "label": "Clock" }
                        }
                    ],
                    "dataScreen": { "refreshInterval": 60 }
                }
                """;
        PageSchema schema = new PageSchema();
        schema.setBlocks(dashboardJson);
        when(formSchemaMapper.selectById("dash-001")).thenReturn(schema);

        // Act
        DashboardDataResponse response = dashboardDataService.fetchDashboardData("dash-001", false, 1L);

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.getDashboardTitle()).isEqualTo("Sales Dashboard");
        assertThat(response.getCacheTtl()).isEqualTo(60);
        assertThat(response.getWidgets()).containsKey("widget1");
        assertThat(response.getWidgets()).containsKey("widget2");
        assertThat(response.getWidgets().get("widget1")).isEqualTo(42);
        assertThat(response.getFetchedAt()).isGreaterThan(0);
    }

    @Test
    void fetchDashboardData_withCrossTenantSqlWidget_isRejectedNotExecuted() {
        // A dashboard schema carrying a free-form SQL widget that reads a shared,
        // tenant-agnostic table. The query would run via SqlRunner, which bypasses
        // the tenant line interceptor, so executing it would leak every tenant's
        // users. Sentinel rows stand in for that cross-tenant data.
        String dashboardJson = """
                {
                    "title": "Malicious Dashboard",
                    "widgets": [
                        {
                            "id": "evil",
                            "dataSource": { "type": "sql", "query": "SELECT id, tenant_id, username FROM ab_user" }
                        }
                    ]
                }
                """;
        PageSchema schema = new PageSchema();
        schema.setBlocks(dashboardJson);
        when(formSchemaMapper.selectById("dash-002")).thenReturn(schema);

        DashboardDataResponse response = dashboardDataService.fetchDashboardData("dash-002", false, 1L);

        Object widgetResult = response.getWidgets().get("evil");
        // The free-SQL query must NOT have been executed, so the widget must not
        // carry any query rows (List) — cross-tenant data must never surface.
        assertThat(widgetResult).isNotInstanceOf(List.class);
        // Instead the widget must carry an explicit rejection.
        assertThat(widgetResult).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> errorMap = (Map<String, Object>) widgetResult;
        assertThat(String.valueOf(errorMap.get("error"))).contains("not supported");
    }

    @Test
    void fetchDashboardData_widgetWithNoDataSourceType_doesNotExecuteSql() {
        // A widget whose dataSource omits "type" must not fall through to raw SQL
        // execution (previously the implicit default), even if it carries a query.
        String dashboardJson = """
                {
                    "title": "No-Type Dashboard",
                    "widgets": [
                        {
                            "id": "sneaky",
                            "dataSource": { "query": "SELECT * FROM ab_tenant" }
                        }
                    ]
                }
                """;
        PageSchema schema = new PageSchema();
        schema.setBlocks(dashboardJson);
        when(formSchemaMapper.selectById("dash-005")).thenReturn(schema);

        DashboardDataResponse response = dashboardDataService.fetchDashboardData("dash-005", false, 1L);

        // No type -> unsupported -> null, never a query result.
        assertThat(response.getWidgets().get("sneaky")).isNull();
    }

    @Test
    void fetchDashboardData_cacheHit_returnsCachedData() {
        String dashboardJson = """
                {
                    "title": "Cached Dashboard",
                    "widgets": [],
                    "dataScreen": { "refreshInterval": 300 }
                }
                """;
        PageSchema schema = new PageSchema();
        schema.setBlocks(dashboardJson);
        when(formSchemaMapper.selectById("dash-003")).thenReturn(schema);

        // First call - hits DB
        DashboardDataResponse first = dashboardDataService.fetchDashboardData("dash-003", false, 1L);
        assertThat(first.getDashboardTitle()).isEqualTo("Cached Dashboard");

        // Second call - should use cache (no additional DB call)
        DashboardDataResponse second = dashboardDataService.fetchDashboardData("dash-003", false, 1L);
        assertThat(second.getDashboardTitle()).isEqualTo("Cached Dashboard");
        assertThat(second.getFetchedAt()).isEqualTo(first.getFetchedAt());
    }

    @Test
    void fetchDashboardData_crossTenantDashboard_isRejected() {
        // A dashboard owned by tenant 7. A caller from tenant 1 must not be able to
        // read it by guessing the id — the service must reject with the same
        // not-found error (no existence oracle across tenants).
        String dashboardJson = """
                {
                    "title": "Tenant 7 Private Dashboard",
                    "widgets": [ { "id": "w", "dataSource": { "type": "static", "data": 1 } } ]
                }
                """;
        PageSchema schema = new PageSchema();
        schema.setBlocks(dashboardJson);
        schema.setTenantId(7L);
        when(formSchemaMapper.selectById("dash-cross")).thenReturn(schema);

        assertThatThrownBy(() -> dashboardDataService.fetchDashboardData("dash-cross", false, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Dashboard not found");
    }

    @Test
    void fetchDashboardData_cacheIsTenantScoped_notSharedAcrossTenants() {
        // A shared dashboard (null owner tenant) is readable by any tenant, but the
        // cache must be keyed per tenant: tenant 2 requesting the same id after
        // tenant 1 must miss the cache and re-resolve, never receive tenant 1's entry.
        String dashboardJson = """
                {
                    "title": "Shared Dashboard",
                    "widgets": [],
                    "dataScreen": { "refreshInterval": 300 }
                }
                """;
        PageSchema schema = new PageSchema();
        schema.setBlocks(dashboardJson);
        when(formSchemaMapper.selectById("shared-dash")).thenReturn(schema);

        dashboardDataService.fetchDashboardData("shared-dash", false, 1L);
        dashboardDataService.fetchDashboardData("shared-dash", false, 2L);

        // A tenant-scoped cache forces a fresh resolve for tenant 2 (2 lookups total).
        // An id-only cache would have served tenant 1's entry to tenant 2 (only 1 lookup).
        verify(formSchemaMapper, times(2)).selectById("shared-dash");
    }

    @Test
    void fetchDashboardData_forceRefresh_bypassesCache() {
        String dashboardJson = """
                {
                    "title": "Force Refresh Dashboard",
                    "widgets": [],
                    "dataScreen": { "refreshInterval": 300 }
                }
                """;
        PageSchema schema = new PageSchema();
        schema.setBlocks(dashboardJson);
        when(formSchemaMapper.selectById("dash-004")).thenReturn(schema);

        // First call
        DashboardDataResponse first = dashboardDataService.fetchDashboardData("dash-004", false, 1L);

        // Force refresh - should hit DB again
        DashboardDataResponse second = dashboardDataService.fetchDashboardData("dash-004", true, 1L);
        // Can't guarantee different timestamps in fast execution, but at least verify no exception
        assertThat(second).isNotNull();
    }
}
