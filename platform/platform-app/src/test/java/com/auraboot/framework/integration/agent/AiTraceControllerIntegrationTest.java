package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.trace.AiTraceController;
import com.auraboot.framework.agent.trace.AiTraceService;
import com.auraboot.framework.agent.trace.TraceContext;
import com.auraboot.framework.agent.trace.dto.TraceDetailResponse;
import com.auraboot.framework.agent.trace.entity.AiTrace;
import com.auraboot.framework.agent.trace.entity.AiTraceSpan;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.integration.TestIdGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AI trace detail API tenant boundary tests.
 *
 * <p>The trace list and stats endpoints were already tenant-scoped. This suite
 * pins the single-trace detail path so direct traceId lookups cannot leak trace
 * rows or spans across tenants.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("AiTraceController — tenant-scoped detail")
class AiTraceControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired private AiTraceController controller;
    @Autowired private AiTraceService traceService;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;

    @BeforeEach
    void setup() {
        tenantId = TestIdGenerator.uniqueTenantId();
        MetaContext.setContext(tenantId, testUser.getId(), testUser.getPid(), testUser.getUserName());
    }

    @AfterEach
    void cleanup() {
        if (tenantId != null) {
            jdbc.update("DELETE FROM ab_gen_ai_usage WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_ai_trace_span WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_ai_trace WHERE tenant_id = ?", tenantId);
        }
    }

    private TraceContext seedCorrelatedTrace(String otelTraceId) {
        String traceId = UUID.randomUUID().toString();
        Instant startTime = Instant.now().minusSeconds(1);
        jdbc.update("INSERT INTO ab_ai_trace " +
                        "(trace_id, otel_trace_id, tenant_id, session_id, name, input, status, metadata, start_time) " +
                        "VALUES (?, ?, ?, 'sess-usage', 'chat', 'input', 'in_progress', '{}'::jsonb, ?)",
                traceId, otelTraceId, tenantId, Timestamp.from(startTime));
        return TraceContext.builder()
                .traceId(traceId)
                .tenantId(tenantId)
                .sessionId("sess-usage")
                .startTime(startTime)
                .build();
    }

    private void seedUsage(String otelTraceId, int inputTokens, int outputTokens, String amount) {
        jdbc.update("INSERT INTO ab_gen_ai_usage " +
                        "(tenant_id, trace_id, provider, request_model, response_model, " +
                        "input_tokens, output_tokens, amount, currency, pricing_version) " +
                        "VALUES (?, ?, 'qianwen', 'qwen-plus', 'qwen-plus', ?, ?, ?::numeric, 'USD', 'test')",
                tenantId, otelTraceId, inputTokens, outputTokens, amount);
    }

    private String seedTrace(Long traceTenantId, String sessionId) {
        String traceId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ab_ai_trace " +
                        "(trace_id, tenant_id, session_id, name, input, output, status, metadata, start_time) " +
                        "VALUES (?, ?, ?, 'chat', 'input', 'output', 'success', '{}'::jsonb, NOW())",
                traceId, traceTenantId, sessionId);
        return traceId;
    }

    private String seedSpan(Long spanTenantId, String traceId, int sequenceOrder) {
        String spanId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ab_ai_trace_span " +
                        "(span_id, trace_id, tenant_id, type, name, input, output, status, level, start_time, sequence_order) " +
                        "VALUES (?, ?, ?, 'llm', 'completion', '{}'::jsonb, '{}'::jsonb, 'success', 'default', NOW(), ?)",
                spanId, traceId, spanTenantId, sequenceOrder);
        return spanId;
    }

    @Test
    @DisplayName("getTrace returns current-tenant trace and spans only")
    void getTrace_returnsCurrentTenantTraceAndSpansOnly() {
        String traceId = seedTrace(tenantId, "sess-1");
        String visibleSpan = seedSpan(tenantId, traceId, 1);
        Long otherTenant = TestIdGenerator.uniqueTenantId();
        seedSpan(otherTenant, traceId, 2);

        try {
            TraceDetailResponse resp = controller.getTrace(traceId);

            assertThat(resp.getTrace()).isNotNull();
            assertThat(resp.getTrace().getTraceId()).isEqualTo(traceId);
            assertThat(resp.getTrace().getTenantId()).isEqualTo(tenantId);
            assertThat(resp.getSpans())
                    .extracting(AiTraceSpan::getSpanId)
                    .containsExactly(visibleSpan);
        } finally {
            jdbc.update("DELETE FROM ab_ai_trace_span WHERE tenant_id = ?", otherTenant);
        }
    }

    @Test
    @DisplayName("getTrace returns 404 for another tenant's traceId")
    void getTrace_otherTenantTraceIdReturns404() {
        Long otherTenant = TestIdGenerator.uniqueTenantId();
        String otherTraceId = seedTrace(otherTenant, "sess-other");

        try {
            assertThatThrownBy(() -> controller.getTrace(otherTraceId))
                    .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                            assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        } finally {
            jdbc.update("DELETE FROM ab_ai_trace_span WHERE tenant_id = ?", otherTenant);
            jdbc.update("DELETE FROM ab_ai_trace WHERE tenant_id = ?", otherTenant);
        }
    }

    @Test
    @DisplayName("endTrace reconciles token and cost totals from the durable usage ledger")
    void endTrace_reconcilesUsageLedgerTotals() {
        String otelTraceId = UUID.randomUUID().toString().replace("-", "");
        MetaContext.setOtelTraceId(otelTraceId);
        TraceContext trace = traceService.createTrace(
                tenantId, "sess-usage", "input", testUser.getId(), Map.of("path", "test"));
        seedUsage(otelTraceId, 120, 30, "0.001200");
        seedUsage(otelTraceId, 80, 20, "0.000800");

        traceService.endTrace(trace, "done", "success");

        AiTrace persisted = traceService.getTrace(tenantId, trace.getTraceId());
        String persistedOtelTraceId = jdbc.queryForObject(
                "SELECT otel_trace_id FROM ab_ai_trace WHERE trace_id = ?",
                String.class,
                trace.getTraceId());
        assertThat(persistedOtelTraceId).isEqualTo(otelTraceId);
        assertThat(persisted.getStatus()).isEqualTo("success");
        assertThat(persisted.getTotalInputTokens()).isEqualTo(200);
        assertThat(persisted.getTotalOutputTokens()).isEqualTo(50);
        assertThat(persisted.getTotalCost()).isEqualByComparingTo("0.002000");
    }

    @Test
    @DisplayName("endTraceWithError also reconciles usage without crossing tenant boundaries")
    void endTraceWithError_reconcilesOnlySameTenantUsage() {
        String otelTraceId = UUID.randomUUID().toString().replace("-", "");
        TraceContext trace = seedCorrelatedTrace(otelTraceId);
        seedUsage(otelTraceId, 300, 40, "0.003000");
        Long otherTenant = TestIdGenerator.uniqueTenantId();
        jdbc.update("INSERT INTO ab_gen_ai_usage " +
                        "(tenant_id, trace_id, provider, request_model, response_model, " +
                        "input_tokens, output_tokens, amount, currency, pricing_version) " +
                        "VALUES (?, ?, 'qianwen', 'qwen-plus', 'qwen-plus', 999, 999, " +
                        "9.999000, 'USD', 'test')",
                otherTenant, otelTraceId);

        try {
            traceService.endTraceWithError(trace, "expected failure");

            AiTrace persisted = traceService.getTrace(tenantId, trace.getTraceId());
            assertThat(persisted.getStatus()).isEqualTo("error");
            assertThat(persisted.getTotalInputTokens()).isEqualTo(300);
            assertThat(persisted.getTotalOutputTokens()).isEqualTo(40);
            assertThat(persisted.getTotalCost()).isEqualByComparingTo("0.003000");
        } finally {
            jdbc.update("DELETE FROM ab_gen_ai_usage WHERE tenant_id = ?", otherTenant);
        }
    }
}
