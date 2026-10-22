package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.service.NamedQueryShadowInvoker;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.NamedQueryTestRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.service.NamedQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR-33: NamedQueryShadowInvoker — wires real dsl.query / nq_* shadow
 * execution via NamedQueryService.
 */
@DisplayName("NamedQueryShadowInvoker (PR-33)")
class NamedQueryShadowInvokerIntegrationTest extends BaseIntegrationTest {

    @Autowired private NamedQueryShadowInvoker invoker;
    @Autowired private JdbcTemplate jdbc;
    @MockBean  private NamedQueryService namedQueryService;

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_named_query WHERE code LIKE 'it_nq_%'");
    }

    /** Seed a minimal ab_named_query row for the given tenant + code. */
    private void seedNamedQuery(long tenantId, String code) {
        jdbc.update("INSERT INTO ab_named_query (pid, tenant_id, code, from_sql) " +
                        "VALUES (?, ?, ?, 'SELECT 1')",
                UniqueIdGenerator.generate(), tenantId, code);
    }

    @Test
    @DisplayName("supports nq_* and dsl.query; rejects cmd_* and null")
    void supports_predicate() {
        assertThat(invoker.supports("nq_crm_leads")).isTrue();
        assertThat(invoker.supports("dsl.query")).isTrue();
        assertThat(invoker.supports("cmd_create_lead")).isFalse();
        assertThat(invoker.supports("nq_")).isTrue();  // prefix OK even if empty tail
        assertThat(invoker.supports(null)).isFalse();
    }

    @Test
    @DisplayName("nq_<code> strips prefix and calls executeQuery with that code")
    void nq_prefix_resolves_code() {
        long tenant = 10L;
        seedNamedQuery(tenant, "it_nq_crm_leads");
        PaginationResult<Map<String, Object>> result = new PaginationResult<>();
        result.setTotal(3L);
        result.setRecords(List.of(Map.of("id", 1), Map.of("id", 2), Map.of("id", 3)));
        when(namedQueryService.executeQuery(eq("it_nq_crm_leads"), any())).thenReturn(result);

        Map<String, Object> out = invoker.invokeShadow(tenant, "nq_it_nq_crm_leads", null);
        assertThat(out.get("query_code")).isEqualTo("it_nq_crm_leads");
        assertThat(out.get("total")).isEqualTo(3L);
        assertThat((List<?>) out.get("rows")).hasSize(3);
    }

    @Test
    @DisplayName("dsl.query pulls code from args.query_code")
    void dsl_query_from_args() {
        long tenant = 10L;
        seedNamedQuery(tenant, "it_nq_orders_today");
        when(namedQueryService.executeQuery(eq("it_nq_orders_today"), any())).thenReturn(new PaginationResult<>());
        Map<String, Object> out = invoker.invokeShadow(tenant, "dsl.query",
                Map.of("query_code", "it_nq_orders_today"));
        assertThat(out.get("query_code")).isEqualTo("it_nq_orders_today");
    }

    @Test
    @DisplayName("dsl.query without query_code returns no_query_code status (never calls service)")
    void dsl_query_missing_code_is_safe() {
        Map<String, Object> out = invoker.invokeShadow(10L, "dsl.query", Map.of());
        assertThat(out.get("status")).isEqualTo("no_query_code");
    }

    @Test
    @DisplayName("pageSize is capped to 50 and parameters forwarded")
    void page_capped_and_params_forwarded() {
        long tenant = 10L;
        seedNamedQuery(tenant, "it_nq_whatever");
        when(namedQueryService.executeQuery(any(), any())).thenReturn(new PaginationResult<>());
        invoker.invokeShadow(tenant, "nq_it_nq_whatever",
                Map.of("parameters", Map.of("customer_id", 42)));

        ArgumentCaptor<NamedQueryTestRequest> captor = ArgumentCaptor.forClass(NamedQueryTestRequest.class);
        verify(namedQueryService).executeQuery(eq("it_nq_whatever"), captor.capture());
        NamedQueryTestRequest req = captor.getValue();
        assertThat(req.getPage()).isEqualTo(1);
        assertThat(req.getSize()).isEqualTo(50);
        assertThat(req.getParameters()).containsEntry("customer_id", 42);
    }

    // =========================================================================
    // C1 — Tenant isolation (cross-tenant refusal + scheduler context pinning)
    // =========================================================================

    @Test
    @DisplayName("C1: tenant_mismatch refuses execution when draft tenant != query owner")
    void tenant_mismatch_refuses_execution() {
        long ownerTenant = 101L;
        long draftTenant = 202L;
        seedNamedQuery(ownerTenant, "it_nq_owned_by_a");

        Map<String, Object> out = invoker.invokeShadow(draftTenant, "nq_it_nq_owned_by_a", null);
        assertThat(out.get("status")).isEqualTo("tenant_mismatch");
        assertThat(out.get("query_code")).isEqualTo("it_nq_owned_by_a");

        // Service must never be called for a cross-tenant query.
        verify(namedQueryService, never()).executeQuery(any(), any());
    }

    @Test
    @DisplayName("C1: MetaContext is pinned to draft tenant inside call and cleared after")
    void scheduler_context_isolation() {
        long tenant = 303L;
        seedNamedQuery(tenant, "it_nq_ctx_probe");

        AtomicReference<Long> observedTenant = new AtomicReference<>();
        when(namedQueryService.executeQuery(eq("it_nq_ctx_probe"), any())).thenAnswer(inv -> {
            // NamedQueryService reads MetaContext via ThreadLocal; capture it here.
            observedTenant.set(MetaContext.getCurrentTenantId());
            return new PaginationResult<>();
        });

        // Simulate scheduler thread with no pre-existing MetaContext.
        MetaContext.clear();
        invoker.invokeShadow(tenant, "nq_it_nq_ctx_probe", null);

        assertThat(observedTenant.get())
                .as("MetaContext tenant must be pinned to draft tenant during executeQuery")
                .isEqualTo(tenant);
        assertThat(MetaContext.exists())
                .as("MetaContext must be cleared in finally after invoker returns")
                .isFalse();
    }
}
