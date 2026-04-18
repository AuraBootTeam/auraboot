package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.service.NamedQueryShadowInvoker;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.NamedQueryTestRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.service.NamedQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * PR-33: NamedQueryShadowInvoker — wires real dsl.query / nq_* shadow
 * execution via NamedQueryService.
 */
@DisplayName("NamedQueryShadowInvoker (PR-33)")
class NamedQueryShadowInvokerIntegrationTest extends BaseIntegrationTest {

    @Autowired private NamedQueryShadowInvoker invoker;
    @MockBean  private NamedQueryService namedQueryService;

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
        PaginationResult<Map<String, Object>> result = new PaginationResult<>();
        result.setTotal(3L);
        result.setRecords(List.of(Map.of("id", 1), Map.of("id", 2), Map.of("id", 3)));
        when(namedQueryService.executeQuery(eq("crm_leads"), any())).thenReturn(result);

        Map<String, Object> out = invoker.invokeShadow(10L, "nq_crm_leads", null);
        assertThat(out.get("query_code")).isEqualTo("crm_leads");
        assertThat(out.get("total")).isEqualTo(3L);
        assertThat((List<?>) out.get("rows")).hasSize(3);
    }

    @Test
    @DisplayName("dsl.query pulls code from args.query_code")
    void dsl_query_from_args() {
        when(namedQueryService.executeQuery(eq("orders_today"), any())).thenReturn(new PaginationResult<>());
        Map<String, Object> out = invoker.invokeShadow(10L, "dsl.query", Map.of("query_code", "orders_today"));
        assertThat(out.get("query_code")).isEqualTo("orders_today");
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
        when(namedQueryService.executeQuery(any(), any())).thenReturn(new PaginationResult<>());
        invoker.invokeShadow(10L, "nq_whatever",
                Map.of("parameters", Map.of("customer_id", 42)));

        ArgumentCaptor<NamedQueryTestRequest> captor = ArgumentCaptor.forClass(NamedQueryTestRequest.class);
        org.mockito.Mockito.verify(namedQueryService).executeQuery(eq("whatever"), captor.capture());
        NamedQueryTestRequest req = captor.getValue();
        assertThat(req.getPage()).isEqualTo(1);
        assertThat(req.getSize()).isEqualTo(50);
        assertThat(req.getParameters()).containsEntry("customer_id", 42);
    }
}
