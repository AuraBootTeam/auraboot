package com.auraboot.framework.behavior.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.behavior.mapper.BehaviorOutcomeOutboxMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;
import static org.mockito.Mockito.*;

class AnalyticsReportUsageServiceTest {
    private final BehaviorOutcomeOutboxMapper origins = mock(BehaviorOutcomeOutboxMapper.class);
    private final AnalyticsJourneyService journey = mock(AnalyticsJourneyService.class);
    private final AnalyticsReportUsageService service = new AnalyticsReportUsageService(origins, journey);
    private final ObjectMapper json = new ObjectMapper();
    @AfterEach void clear() { MetaContext.clear(); }

    @Test void usesCurrentTenantAndDoesNotInventOrigin() {
        MetaContext.setContext(42L, 7L, "actor", "tester");
        service.exported("report", json.createObjectNode(), UUID.randomUUID(), "json");
        verify(origins).findReportOrigin(42L, "report");
        verifyNoInteractions(journey);
    }

    @Test void distinguishesOriginalAndChangedQuery() {
        MetaContext.setContext(42L, 7L, "actor", "tester");
        var query = json.createObjectNode().put("type", "aggregate").put("limit", 5);
        var dsl = json.createObjectNode();
        dsl.putObject("dataSources").putObject("analysis").put("type", "aggregate").set("aggregateQuery", query);
        String original = AnalyticsQueryFingerprint.of(query);
        when(origins.findReportOrigin(42L, "report")).thenReturn(Map.of("analysisId", "analysis", "queryHash", original));
        UUID action = UUID.randomUUID();
        service.exported("report", dsl, action, "json");
        verify(journey).reportExported("analysis", "report", action, original, true, "json");
        query.put("limit", 6);
        service.exported("report", dsl, action, "json");
        verify(journey).reportExported("analysis", "report", action, AnalyticsQueryFingerprint.of(query), false, "json");
    }
}
