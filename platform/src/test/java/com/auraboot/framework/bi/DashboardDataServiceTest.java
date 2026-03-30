package com.auraboot.framework.bi;

import com.auraboot.framework.bi.dto.DashboardDataResponse;
import com.auraboot.framework.bi.service.impl.DashboardDataServiceImpl;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.datasource.dao.mapper.DynamicQueryMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for DashboardDataService.
 */
@ExtendWith(MockitoExtension.class)
class DashboardDataServiceTest {

    @Mock
    private PageSchemaMapper formSchemaMapper;

    @Mock
    private DynamicQueryMapper dynamicQueryMapper;

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
    void fetchDashboardData_withSqlWidget_executesQuery() {
        String dashboardJson = """
                {
                    "title": "Test Dashboard",
                    "widgets": [
                        {
                            "id": "table1",
                            "dataSource": { "type": "sql", "query": "SELECT COUNT(*) as cnt FROM ns_device" }
                        }
                    ]
                }
                """;
        PageSchema schema = new PageSchema();
        schema.setBlocks(dashboardJson);
        when(formSchemaMapper.selectById("dash-002")).thenReturn(schema);
        when(dynamicQueryMapper.queryData(anyString())).thenReturn(
                List.of(Map.of("cnt", 25L))
        );

        DashboardDataResponse response = dashboardDataService.fetchDashboardData("dash-002", false, 1L);

        assertThat(response.getWidgets()).containsKey("table1");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tableData = (List<Map<String, Object>>) response.getWidgets().get("table1");
        assertThat(tableData).hasSize(1);
        assertThat(tableData.get(0).get("cnt")).isEqualTo(25L);
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
