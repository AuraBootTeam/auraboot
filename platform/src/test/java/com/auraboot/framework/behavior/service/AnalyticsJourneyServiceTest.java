package com.auraboot.framework.behavior.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.behavior.dto.BehaviorEventInput;
import com.auraboot.framework.behavior.ingest.BehaviorIngestPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AnalyticsJourneyServiceTest {
    private final BehaviorIngestPublisher publisher = mock(BehaviorIngestPublisher.class);
    private final AnalyticsJourneyService service = new AnalyticsJourneyService(publisher);

    @AfterEach
    void clear() { MetaContext.clear(); }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void stagesShareServerGeneratedIdentityAndOnlySafeSummaryProps() {
        MetaContext.setCurrentTenantId(42L);
        MetaContext.setCurrentUserId(7L);
        String id = service.requested();
        com.fasterxml.jackson.databind.JsonNode query = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("modelCode", "orders");
        service.succeeded(id, 3, query);
        ArgumentCaptor<List<BehaviorEventInput>> batches = ArgumentCaptor.forClass((Class) List.class);
        verify(publisher, times(2)).publish(eq(42L), eq(7L), batches.capture());
        List<BehaviorEventInput> events = batches.getAllValues().stream().flatMap(List::stream).toList();
        assertThat(events).extracting(BehaviorEventInput::getEventName)
                .containsExactly("analytics_requested", "analytics_query_succeeded");
        assertThat(events).extracting(BehaviorEventInput::getInteractionId).containsOnly(id);
        assertThat(events).extracting(BehaviorEventInput::getEventId).doesNotHaveDuplicates();
        assertThat(events.get(0).getProps()).isEmpty();
        assertThat(events.get(1).getProps()).isEqualTo(Map.of("rowCount", 3, "queryHash", AnalyticsQueryFingerprint.of(query)));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void dashboardUseIsIdempotentWithinVisitAndScopedToViewer() {
        MetaContext.setContext(42L, 7L, "viewer", "viewer");
        var visit = java.util.UUID.randomUUID();
        service.dashboardUsed("analysis", "dashboard", "widget", visit, "hash", true);
        service.dashboardUsed("analysis", "dashboard", "widget", visit, "hash", true);
        service.dashboardUsed("analysis", "dashboard", "widget", java.util.UUID.randomUUID(), "hash", true);
        MetaContext.setCurrentUserId(8L);
        service.dashboardUsed("analysis", "dashboard", "widget", visit, "hash", true);
        ArgumentCaptor<List<BehaviorEventInput>> batches = ArgumentCaptor.forClass((Class) List.class);
        verify(publisher, times(4)).publish(eq(42L), anyLong(), batches.capture());
        var ids = batches.getAllValues().stream().map(batch -> batch.getFirst().getEventId()).toList();
        assertThat(ids.get(0)).isEqualTo(ids.get(1));
        assertThat(List.of(ids.get(0), ids.get(2), ids.get(3))).doesNotHaveDuplicates();
    }

    @Test
    void missingContextDoesNotPublishUnownedEvents() {
        assertThatThrownBy(service::requested).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(publisher);
    }
}
