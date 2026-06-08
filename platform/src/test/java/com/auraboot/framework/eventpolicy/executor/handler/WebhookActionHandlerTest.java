package com.auraboot.framework.eventpolicy.executor.handler;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;
import com.auraboot.framework.webhook.service.WebhookDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit test for {@link WebhookActionHandler} — fans out to WebhookDispatcher with the right event
 * type / body / tenant. Real-stack delivery is @Async + needs a webhook subscription, so the async
 * delivery-log IT is a documented follow-on (gap tracker); this verifies the dispatch contract.
 */
class WebhookActionHandlerTest {

    private final WebhookDispatcher dispatcher = mock(WebhookDispatcher.class);
    private final WebhookActionHandler handler = new WebhookActionHandler(dispatcher);

    @AfterEach
    void clear() {
        MetaContext.clear();
    }

    private ResolvedActionPlan plan(Map<String, Object> payload) {
        return new ResolvedActionPlan("R-1", "WEBHOOK", "https://x", 10, payload, "idem-1");
    }

    @Test
    void dispatchesEventWithBodyAndTenant() {
        MetaContext.setCurrentTenantId(42L);
        handler.execute(plan(Map.of("eventType", "complaint.escalated", "caseId", "CMP-1")),
                DecisionContext.of(Map.of()));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(dispatcher).dispatch(eq("complaint.escalated"), body.capture(), eq(42L));
        assertThat(body.getValue()).containsEntry("caseId", "CMP-1").doesNotContainKey("eventType");
    }

    @Test
    void throwsWhenEventTypeMissing() {
        MetaContext.setCurrentTenantId(42L);
        assertThatThrownBy(() -> handler.execute(plan(Map.of("caseId", "CMP-1")), DecisionContext.of(Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void throwsWhenNoTenantContext() {
        assertThatThrownBy(() -> handler.execute(plan(Map.of("eventType", "x")), DecisionContext.of(Map.of())))
                .isInstanceOf(IllegalStateException.class);
    }
}
