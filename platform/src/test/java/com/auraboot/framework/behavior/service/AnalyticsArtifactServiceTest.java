package com.auraboot.framework.behavior.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.behavior.mapper.BehaviorEventMapper;
import com.auraboot.framework.behavior.outcome.BehaviorOutcomePublisher;
import com.auraboot.framework.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalyticsArtifactServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final BehaviorEventMapper events = mock(BehaviorEventMapper.class);
    private final BehaviorOutcomePublisher outcomes = mock(BehaviorOutcomePublisher.class);
    private final AnalyticsArtifactService service = new AnalyticsArtifactService(events, outcomes);

    @AfterEach
    void clear() { MetaContext.clear(); }

    private ArrayNode widgets(ObjectNode source) {
        ArrayNode widgets = json.createArrayNode();
        widgets.addObject().putObject("config").set("dataSource", source);
        return widgets;
    }

    @Test
    void lookupUsesCurrentTenantAndUserAndRejectsDifferentQuery() {
        MetaContext.setCurrentTenantId(42L);
        MetaContext.setCurrentUserId(7L);
        ObjectNode query = json.createObjectNode().put("modelCode", "orders").put("limit", 5);
        String hash = AnalyticsQueryFingerprint.of(query);
        when(events.findSuccessfulQueryHash(42L, 7L, "analysis")).thenReturn(hash);
        assertThat(service.verifyQuery("analysis", widgets(query))).isEqualTo(hash);
        assertThatThrownBy(() -> service.verifyQuery("analysis", widgets(query.deepCopy().put("limit", 6))))
                .isInstanceOf(BusinessException.class);
        verify(events, times(2)).findSuccessfulQueryHash(42L, 7L, "analysis");
        verifyNoInteractions(outcomes);
    }

    @Test
    void missingOrOtherOwnerQueryCannotBeClaimed() {
        MetaContext.setCurrentTenantId(99L);
        MetaContext.setCurrentUserId(8L);
        assertThatThrownBy(() -> service.verifyQuery("foreign", widgets(json.createObjectNode())))
                .isInstanceOf(BusinessException.class);
        verify(events).findSuccessfulQueryHash(99L, 8L, "foreign");
        verifyNoInteractions(outcomes);
    }

    @Test
    void fingerprintIgnoresObjectOrderButRetainsQuerySemantics() throws Exception {
        assertThat(AnalyticsQueryFingerprint.of(json.readTree("{\"filters\":{\"a\":1,\"b\":2},\"limit\":5}")))
                .isEqualTo(AnalyticsQueryFingerprint.of(json.readTree("{\"limit\":5,\"filters\":{\"b\":2,\"a\":1}}")))
                .isNotEqualTo(AnalyticsQueryFingerprint.of(json.readTree("{\"limit\":6,\"filters\":{\"b\":2,\"a\":1}}")));
    }

    @Test
    void reportSourceMustMatchSuccessfulQueryAndRejectsAdditionalSources() {
        MetaContext.setContext(42L, 7L, "actor", "tester");
        ObjectNode query = json.createObjectNode().put("type", "aggregate").put("modelCode", "orders").put("limit", 5);
        String hash = AnalyticsQueryFingerprint.of(query);
        when(events.findSuccessfulQueryHash(42L, 7L, "analysis")).thenReturn(hash);
        ObjectNode dsl = json.createObjectNode();
        ObjectNode sources = dsl.putObject("dataSources");
        sources.putObject("analysis").put("type", "aggregate").set("aggregateQuery", query);
        assertThat(service.verifyReportQuery("analysis", dsl)).isEqualTo(hash);
        query.put("limit", 6);
        assertThatThrownBy(() -> service.verifyReportQuery("analysis", dsl)).isInstanceOf(BusinessException.class);
        query.put("limit", 5);
        sources.putObject("extra").put("type", "static");
        assertThatThrownBy(() -> service.verifyReportQuery("analysis", dsl)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(outcomes);
    }

    @Test
    void reportOutcomeCarriesServerActorAndArtifactIdentity() {
        MetaContext.setContext(42L, 7L, "actor", "tester");
        service.reportSaved("analysis", "report", "hash");
        var event = org.mockito.ArgumentCaptor.forClass(com.auraboot.framework.behavior.outcome.BehaviorOutcomeEvent.class);
        verify(outcomes).publish(event.capture());
        assertThat(event.getValue().getTenantId()).isEqualTo(42L);
        assertThat(event.getValue().getUserId()).isEqualTo(7L);
        assertThat(event.getValue().getEventName()).isEqualTo("analytics_report_saved");
        assertThat(event.getValue().getTargetType()).isEqualTo("report");
        assertThat(event.getValue().getTargetKey()).isEqualTo("report");
        assertThat(event.getValue().getInteractionId()).isEqualTo("analysis");
        assertThat(event.getValue().getProps()).containsEntry("queryHash", "hash");
    }
}
