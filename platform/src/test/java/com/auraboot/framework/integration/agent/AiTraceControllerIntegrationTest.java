package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.trace.AiTraceController;
import com.auraboot.framework.agent.trace.dto.TraceDetailResponse;
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
            jdbc.update("DELETE FROM ab_ai_trace_span WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_ai_trace WHERE tenant_id = ?", tenantId);
        }
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
}
