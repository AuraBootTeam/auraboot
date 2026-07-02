package com.auraboot.framework.bi.service.impl;

import com.auraboot.framework.bi.dto.DashboardDataResponse;
import com.auraboot.framework.datasource.dao.mapper.DynamicQueryMapper;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the SELECT-only guard on dashboard {@code type:sql} widgets.
 *
 * <p>Security regression: the widget's {@code dataSource.query} (authored config
 * stored in {@code ab_page_schema}) was executed via {@code SqlRunner} with no
 * validation — the one BI path that skipped {@code SqlSafetyUtils.validateSelectOnlySql}
 * that all sibling raw-SQL paths enforce. A {@code PAGE_SCHEMA_MANAGE} author could
 * run non-SELECT statements / UNION-based exfiltration. The guard now rejects those
 * before {@code DynamicQueryMapper.queryData} is ever called.
 */
@ExtendWith(MockitoExtension.class)
class DashboardDataServiceImplSqlGuardTest {

    @Mock
    private PageSchemaMapper formSchemaMapper;
    @Mock
    private DynamicQueryMapper dynamicQueryMapper;

    private DashboardDataServiceImpl service() {
        return new DashboardDataServiceImpl(formSchemaMapper, dynamicQueryMapper);
    }

    private void stubDashboardWithSql(String dashboardId, String query) {
        PageSchema schema = new PageSchema();
        schema.setBlocks("{\"widgets\":[{\"id\":\"w1\",\"dataSource\":"
                + "{\"type\":\"sql\",\"query\":\"" + query + "\"}}]}");
        when(formSchemaMapper.selectById(dashboardId)).thenReturn(schema);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        assertThat(o).isInstanceOf(Map.class);
        return (Map<String, Object>) o;
    }

    @Test
    void nonSelectWidgetQueryIsRejectedBeforeExecution() {
        stubDashboardWithSql("dash-del", "DELETE FROM ab_user");
        DashboardDataResponse resp = service().fetchDashboardData("dash-del", true, 1L);

        assertThat(asMap(resp.getWidgets().get("w1"))).containsKey("error");
        verify(dynamicQueryMapper, never()).queryData(anyString());
    }

    @Test
    void unionBasedExfiltrationIsRejectedBeforeExecution() {
        stubDashboardWithSql("dash-union",
                "SELECT id FROM ab_page_schema UNION SELECT password FROM ab_user");
        DashboardDataResponse resp = service().fetchDashboardData("dash-union", true, 1L);

        assertThat(asMap(resp.getWidgets().get("w1"))).containsKey("error");
        verify(dynamicQueryMapper, never()).queryData(anyString());
    }

    @Test
    void plainSelectWidgetQueryIsExecuted() {
        stubDashboardWithSql("dash-ok", "SELECT status, count(*) c FROM mt_orders GROUP BY status");
        when(dynamicQueryMapper.queryData(eq("SELECT status, count(*) c FROM mt_orders GROUP BY status")))
                .thenReturn(List.of(Map.of("status", "open", "c", 3)));

        DashboardDataResponse resp = service().fetchDashboardData("dash-ok", true, 1L);

        assertThat(resp.getWidgets().get("w1")).isEqualTo(List.of(Map.of("status", "open", "c", 3)));
        verify(dynamicQueryMapper).queryData(eq("SELECT status, count(*) c FROM mt_orders GROUP BY status"));
    }
}
