package com.auraboot.framework.behavior;

import com.auraboot.framework.agent.dto.ResultContract;
import com.auraboot.framework.behavior.service.AnalyticsResultHistory;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.AggregateQueryResponse;
import com.auraboot.framework.bi.service.ReportAggregateQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AnalyticsResultHistoryTest {
    @Test void persistsOnlyReferencesAndRequeriesInsteadOfReplayingSensitiveRows() {
        ObjectMapper json = new ObjectMapper();
        ReportAggregateQueryService queries = mock(ReportAggregateQueryService.class);
        var contract = ResultContract.builder().skillCode("aurabot:chat-bi").status("success")
                .data(Map.of("data", Map.of("analysisId", "analysis-1", "dataSource", Map.of("type", "aggregate", "modelCode", "orders"),
                        "chartType", "table", "records", List.of(Map.of("secret", "old-value")), "interpretation", "private conclusion"))).build();
        var references = AnalyticsResultHistory.references(List.of(contract));
        assertEquals(1, references.size());
        assertFalse(references.toString().contains("old-value"));
        assertFalse(references.toString().contains("private conclusion"));
        var current = new AggregateQueryResponse();
        current.setRows(List.of(Map.of("count", 2)));
        when(queries.execute(any())).thenReturn(current);
        var restored = new AnalyticsResultHistory(queries, json).restore(json.valueToTree(references));
        assertEquals(List.of(Map.of("count", 2)), restored.get(0).getData().get("records"));
        assertEquals(true, restored.get(0).getData().get("historyRefreshed"));
        assertEquals("analysis-1", restored.get(0).getData().get("analysisId"));
        verify(queries).execute(argThat(query -> "orders".equals(query.getModelCode())));
    }

    @Test void deniedHistoryDoesNotExposeQueryOrStoredData() {
        var queries = mock(ReportAggregateQueryService.class);
        when(queries.execute(any())).thenThrow(new org.springframework.security.access.AccessDeniedException("private denied field"));
        var json = new ObjectMapper();
        var restored = new AnalyticsResultHistory(queries, json).restore(json.valueToTree(List.of(Map.of(
                "analysisId", "analysis-1", "dataSource", Map.of("type", "aggregate", "modelCode", "private_model")))));
        assertEquals("failed", restored.get(0).getStatus());
        assertNull(restored.get(0).getData());
        assertFalse(restored.get(0).getTextSummary().contains("private"));
    }
}
