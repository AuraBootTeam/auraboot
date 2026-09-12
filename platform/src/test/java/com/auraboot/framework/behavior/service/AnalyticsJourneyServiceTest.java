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
        service.succeeded(id, 3);
        ArgumentCaptor<List<BehaviorEventInput>> batches = ArgumentCaptor.forClass((Class) List.class);
        verify(publisher, times(2)).publish(eq(42L), eq(7L), batches.capture());
        List<BehaviorEventInput> events = batches.getAllValues().stream().flatMap(List::stream).toList();
        assertThat(events).extracting(BehaviorEventInput::getEventName)
                .containsExactly("analytics_requested", "analytics_query_succeeded");
        assertThat(events).extracting(BehaviorEventInput::getInteractionId).containsOnly(id);
        assertThat(events).extracting(BehaviorEventInput::getEventId).doesNotHaveDuplicates();
        assertThat(events.get(0).getProps()).isEmpty();
        assertThat(events.get(1).getProps()).isEqualTo(Map.of("rowCount", 3));
    }

    @Test
    void missingContextDoesNotPublishUnownedEvents() {
        assertThatThrownBy(service::requested).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(publisher);
    }
}
