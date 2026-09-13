package com.auraboot.framework.behavior.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.behavior.service.AnalyticsSuggestionCommandHandler;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Owner-scoped, presentation-only projection; query snapshots and request keys stay private. */
@RestController
@RequestMapping("/api/analytics/suggestions")
@RequiredArgsConstructor
public class AnalyticsSuggestionController {
    private final DynamicDataService data;
    private final com.auraboot.framework.bi.service.ReportAggregateQueryService queries;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    private final com.auraboot.framework.behavior.service.AnalyticsExecutionStatusService executions;
    private static final String PREFIX = "core_dashboard_";
    public record Suggestion(String pid, String title, String content, int version, String groupKey,
                             String origin, String adoptionPid, String decisionMode, String executionGoal,
                             com.auraboot.framework.behavior.service.AnalyticsExecutionStatusService.Status execution) {}
    public record Page(List<Suggestion> records, long total, int page, int pageSize) {}

    @GetMapping
    @RequirePermission("analytics.suggestion.read")
    public ApiResponse<Page> list(@RequestParam String analysisId, @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "5") int pageSize) {
        if (analysisId.isBlank() || analysisId.length() > 40 || page < 1 || pageSize < 1 || pageSize > 50
                || MetaContext.getCurrentTenantId() == null || MetaContext.getCurrentUserId() == null) {
            throw new BusinessException(ResponseCode.BadParam, "Invalid suggestion page request");
        }
        var versions = data.list(AnalyticsSuggestionCommandHandler.VERSION, DynamicQueryRequest.builder()
                .pageNum(page).pageSize(pageSize)
                .conditions(List.of(eq(PREFIX + "analysis_id", analysisId), eq("created_by", MetaContext.getCurrentUserId())))
                .sortFields(List.of(SortField.builder().fieldName("created_at").direction(SortField.SortDirection.DESC).build(),
                        SortField.builder().fieldName("pid").direction(SortField.SortDirection.DESC).build())).build());
        var sourceRows = versions.getRecords();
        if (sourceRows.isEmpty() && versions.getTotal() > 0) {
            // Out-of-range pages must not disclose a count after source permission revocation.
            sourceRows = data.list(AnalyticsSuggestionCommandHandler.VERSION, DynamicQueryRequest.builder()
                    .pageNum(1).pageSize(1).conditions(List.of(eq(PREFIX + "analysis_id", analysisId),
                            eq("created_by", MetaContext.getCurrentUserId()))).build()).getRecords();
            if (sourceRows.isEmpty()) throw new BusinessException(ResponseCode.BadParam, "Suggestion source is unavailable");
        }
        if (!sourceRows.isEmpty()) authorizeSource(sourceRows.get(0));
        Map<String, Map<String, Object>> decisions = new HashMap<>();
        if (!versions.getRecords().isEmpty()) {
            List<Object> pids = versions.getRecords().stream().map(row -> row.get("pid")).toList();
            var adoptions = data.list(AnalyticsSuggestionCommandHandler.ADOPTION, DynamicQueryRequest.builder()
                    .pageNum(1).pageSize(pageSize).conditions(List.of(eq("created_by", MetaContext.getCurrentUserId()),
                            QueryCondition.builder().fieldName(PREFIX + "version_pid").operator(QueryCondition.Operator.IN).values(pids).build())).build());
            for (var adoption : adoptions.getRecords()) decisions.put(String.valueOf(adoption.get(PREFIX + "version_pid")), adoption);
        }
        var statuses = executions.read(decisions.values().stream().map(decision -> String.valueOf(decision.get("pid"))).toList());
        List<Suggestion> records = versions.getRecords().stream().map(row -> {
            String pid = String.valueOf(row.get("pid"));
            Map<String, Object> decision = decisions.get(pid);
            return new Suggestion(pid, String.valueOf(row.get(PREFIX + "title")), String.valueOf(row.get(PREFIX + "content")),
                    ((Number) row.get(PREFIX + "version")).intValue(), String.valueOf(row.get(PREFIX + "group_key")),
                    String.valueOf(row.get(PREFIX + "origin")), decision == null ? null : String.valueOf(decision.get("pid")),
                    decision == null ? null : String.valueOf(decision.get(PREFIX + "decision_mode")), executionGoal(row),
                    decision == null ? null : statuses.get(String.valueOf(decision.get("pid"))));
        }).toList();
        return ApiResponse.success(new Page(records, versions.getTotal(), page, pageSize));
    }
    private void authorizeSource(Map<String, Object> row) {
        Object snapshot = row.get(PREFIX + "query");
        if (!(snapshot instanceof String value)) {
            throw new BusinessException(ResponseCode.BadParam, "Stored suggestion query is unavailable");
        }
        try {
            queries.execute(json.readValue(value, AggregateQueryRequest.class));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new BusinessException(ResponseCode.BadParam, "Stored suggestion query is invalid");
        }
    }
    private String executionGoal(Map<String, Object> row) {
        Object stored = row.get(PREFIX + "execution_intent");
        if (stored == null) return null;
        try {
            return com.auraboot.framework.behavior.service.AnalyticsExecutionIntent.parse(
                    json.readValue(String.valueOf(stored), Map.class)).goal();
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new BusinessException(ResponseCode.BadParam, "Stored execution intent is invalid");
        }
    }
    private static QueryCondition eq(String field, Object value) {
        return QueryCondition.builder().fieldName(field).operator(QueryCondition.Operator.EQ).value(value).build();
    }
}
