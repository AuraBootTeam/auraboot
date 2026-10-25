package com.auraboot.framework.behavior.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.behavior.mapper.BehaviorEventMapper;
import com.auraboot.framework.behavior.outcome.AnalyticsCommandOutcomePublisher;
import com.auraboot.framework.behavior.outcome.BehaviorOutcomePublisher;
import com.auraboot.framework.bi.service.ReportAggregateQueryService;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension.CommandContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Missing execution adapters must not disable manual analysis or fabricate old run results. */
class OptionalAnalyticsConsumerTest {
    @BeforeEach void tenant() { MetaContext.setContext(1L, 2L, "user", "user"); }
    @AfterEach void clear() { MetaContext.clear(); }

    @Test
    void noRequestedExecutionReferencesRemainAnEmptyBatch() {
        assertThat(new AnalyticsExecutionStatusService(null).read(List.of())).isEmpty();
    }

    @Test
    void requestedExecutionStatusDoesNotBecomeNotStartedWhenCapabilityIsMissing() {
        assertThatThrownBy(() -> new AnalyticsExecutionStatusService(null).read(List.of("old-adoption")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("not installed");
    }

    @Test
    void requestedResultsFailBeforeQueryingSourceRecordsOrConstructingAnEmptyPage() {
        var sources = mock(AnalyticsExecutionSourceService.class);
        var data = mock(DynamicDataService.class);
        var service = new AnalyticsBusinessResultService(sources, null, data, null, null, null, null);
        assertThatThrownBy(() -> service.read("old-adoption", 1, 10))
                .isInstanceOf(BusinessException.class).hasMessageContaining("not installed");
        verifyNoInteractions(sources, data);
    }

    @Test
    void ordinaryCommandsDoNotQueryAgentOriginsOrWriteAnalyticsExecutionEvents() {
        var jdbc = mock(JdbcTemplate.class);
        var outcomes = mock(BehaviorOutcomePublisher.class);
        var publisher = new AnalyticsCommandOutcomePublisher(jdbc, new ObjectMapper(), outcomes, null, null, null);
        publisher.record(mock(CommandPipelineContext.class));
        verifyNoInteractions(jdbc, outcomes);
    }

    @Test
    void aSuppliedExecutionContextCannotHideAMissingOriginReader() {
        var execution = mock(AnalyticsExecutionContext.class);
        when(execution.runPid()).thenReturn("old-run");
        var ctx = mock(CommandPipelineContext.class);
        when(ctx.getRequest()).thenReturn(new CommandExecuteRequest());
        when(ctx.getExecConfig()).thenReturn(Map.of("type", "create"));
        var jdbc = mock(JdbcTemplate.class);
        var outcomes = mock(BehaviorOutcomePublisher.class);
        var publisher = new AnalyticsCommandOutcomePublisher(jdbc, new ObjectMapper(), outcomes, null, execution, null);
        assertThatThrownBy(() -> publisher.record(ctx)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("not installed");
        verifyNoInteractions(jdbc, outcomes);
    }

    @Test
    void manualProposalKeepsItsHumanOriginWithoutExecutionContext() {
        var fixture = new ManualFixture();
        Map<String, Object> payload = Map.of("analysisId", "analysis", "title", "A proposal", "content", "Review orders",
                "query", fixture.query, "requestId", UUID.randomUUID().toString());
        fixture.execute(AnalyticsSuggestionCommandHandler.PROPOSE, payload);
        verify(fixture.data).create(eq(AnalyticsSuggestionCommandHandler.VERSION), argThat(record ->
                "human_authored".equals(record.get("core_dashboard_origin"))
                && fixture.hash.equals(record.get("core_dashboard_query_hash"))));
        verify(fixture.outcomes).publish(argThat(event -> "analytics_suggestion_proposed".equals(event.getEventName())));
    }

    @Test
    void manualAdoptionRetainsProvenanceAndPersistenceWithoutExecutionContext() {
        var fixture = new ManualFixture();
        var version = new LinkedHashMap<String, Object>();
        version.put("created_by", 2L);
        version.put("core_dashboard_analysis_id", "analysis");
        version.put("core_dashboard_query", "{\"modelCode\":\"orders\"}");
        version.put("core_dashboard_query_hash", fixture.hash);
        version.put("core_dashboard_origin", "human_authored");
        when(fixture.data.getById(AnalyticsSuggestionCommandHandler.VERSION, "version")).thenReturn(version);
        fixture.execute(AnalyticsSuggestionCommandHandler.ADOPT,
                Map.of("versionPid", "version", "requestId", UUID.randomUUID().toString()));
        verify(fixture.data).create(eq(AnalyticsSuggestionCommandHandler.ADOPTION), argThat(record ->
                "human".equals(record.get("core_dashboard_decision_mode"))
                && "version".equals(record.get("core_dashboard_version_pid"))));
        verify(fixture.outcomes).publish(argThat(event -> "analytics_suggestion_adopted".equals(event.getEventName())));
    }

    private static final class ManualFixture {
        final DynamicDataService data = mock(DynamicDataService.class);
        final BehaviorOutcomePublisher outcomes = mock(BehaviorOutcomePublisher.class);
        final ObjectMapper json = new ObjectMapper();
        final Map<String, Object> query = Map.of("modelCode", "orders");
        final String hash = AnalyticsQueryFingerprint.of(json.valueToTree(query));
        final AnalyticsSuggestionCommandHandler handler;
        ManualFixture() {
            var events = mock(BehaviorEventMapper.class);
            when(events.findSuccessfulQueryHash(1L, 2L, "analysis")).thenReturn(hash);
            var empty = new PaginationResult<Map<String, Object>>();
            empty.setRecords(List.of());
            when(data.list(anyString(), any())).thenReturn(empty);
            when(data.create(anyString(), anyMap())).thenAnswer(invocation -> {
                var row = new LinkedHashMap<String, Object>(invocation.getArgument(1));
                row.put("pid", "created-record");
                return row;
            });
            when(outcomes.publish(any())).thenReturn(true);
            handler = new AnalyticsSuggestionCommandHandler(data, mock(DynamicDataMapper.class), events,
                    mock(ReportAggregateQueryService.class), outcomes, json, null);
        }
        void execute(String command, Map<String, Object> payload) {
            var context = new CommandContext(1L, "core-dashboard", "core_dashboard", command, null, null,
                    payload, Map.of(), false);
            MetaContext.runWithCommandPermitScope("ALL", () -> MetaContext.runWithAuthorizedCommandCode(
                    command, () -> handler.execute(context)));
        }
    }
}
