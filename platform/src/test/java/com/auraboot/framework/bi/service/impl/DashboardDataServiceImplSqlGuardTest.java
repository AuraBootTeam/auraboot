package com.auraboot.framework.bi.service.impl;

import com.auraboot.framework.bi.dto.DashboardDataResponse;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Security regression suite for dashboard {@code type:sql} widgets.
 *
 * <p>History: the widget's {@code dataSource.query} (authored config stored in
 * {@code ab_page_schema}) was executed via {@code SqlRunner}. An initial fix added
 * {@code SqlSafetyUtils.validateSelectOnlySql}, which blocks writes/DDL/stacked
 * statements/UNION/comments/file-ops — but a <em>plain</em> {@code SELECT} still ran
 * through {@code SqlRunner}, which bypasses the tenant line interceptor. Because
 * {@code ab_user}/{@code ab_tenant}/{@code ab_user_role} are in the interceptor's
 * ignore set, a {@code PAGE_SCHEMA_MANAGE} author could exfiltrate every tenant's
 * users/tokens with {@code SELECT * FROM ab_user} (finding
 * DR-20260702-SD2-DASHBOARD-003 residual / FU-1).
 *
 * <p>Resolution: the free-form SQL path is removed entirely. No {@code type:sql}
 * widget executes; the Dashboard Designer only produces tenant-scoped data sources
 * (aggregate / namedQuery / static). These tests assert every free-SQL shape —
 * including a plain cross-tenant {@code SELECT} — is rejected and never executed.
 */
@ExtendWith(MockitoExtension.class)
class DashboardDataServiceImplSqlGuardTest {

    @Mock
    private PageSchemaMapper formSchemaMapper;

    private DashboardDataServiceImpl service() {
        return new DashboardDataServiceImpl(formSchemaMapper);
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
    void nonSelectWidgetQueryIsRejected() {
        stubDashboardWithSql("dash-del", "DELETE FROM ab_user");
        DashboardDataResponse resp = service().fetchDashboardData("dash-del", true, 1L);

        Object w = resp.getWidgets().get("w1");
        assertThat(w).isNotInstanceOf(List.class);
        assertThat(asMap(w)).containsKey("error");
    }

    @Test
    void unionBasedExfiltrationIsRejected() {
        stubDashboardWithSql("dash-union",
                "SELECT id FROM ab_page_schema UNION SELECT password FROM ab_user");
        DashboardDataResponse resp = service().fetchDashboardData("dash-union", true, 1L);

        Object w = resp.getWidgets().get("w1");
        assertThat(w).isNotInstanceOf(List.class);
        assertThat(asMap(w)).containsKey("error");
    }

    @Test
    void plainCrossTenantSelectIsRejected() {
        // The residual hole: a syntactically valid SELECT against a shared,
        // tenant-agnostic table. It used to execute (SqlRunner bypasses the tenant
        // line interceptor) and leak every tenant's users. It must now be rejected.
        stubDashboardWithSql("dash-cross", "SELECT id, tenant_id, username FROM ab_user");
        DashboardDataResponse resp = service().fetchDashboardData("dash-cross", true, 1L);

        Object w = resp.getWidgets().get("w1");
        assertThat(w).isNotInstanceOf(List.class);
        assertThat(asMap(w)).containsKey("error");
    }
}
