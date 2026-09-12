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
    private static final String PREFIX = "core_dashboard_";
    public record Suggestion(String pid, String title, String content, int version, String groupKey,
                             String origin, String adoptionPid, String decisionMode) {}
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
        Map<String, Map<String, Object>> decisions = new HashMap<>();
        if (!versions.getRecords().isEmpty()) {
            List<Object> pids = versions.getRecords().stream().map(row -> row.get("pid")).toList();
            var adoptions = data.list(AnalyticsSuggestionCommandHandler.ADOPTION, DynamicQueryRequest.builder()
                    .pageNum(1).pageSize(pageSize).conditions(List.of(eq("created_by", MetaContext.getCurrentUserId()),
                            QueryCondition.builder().fieldName(PREFIX + "version_pid").operator(QueryCondition.Operator.IN).values(pids).build())).build());
            for (var adoption : adoptions.getRecords()) decisions.put(String.valueOf(adoption.get(PREFIX + "version_pid")), adoption);
        }
        List<Suggestion> records = versions.getRecords().stream().map(row -> {
            String pid = String.valueOf(row.get("pid"));
            Map<String, Object> decision = decisions.get(pid);
            return new Suggestion(pid, String.valueOf(row.get(PREFIX + "title")), String.valueOf(row.get(PREFIX + "content")),
                    ((Number) row.get(PREFIX + "version")).intValue(), String.valueOf(row.get(PREFIX + "group_key")),
                    String.valueOf(row.get(PREFIX + "origin")), decision == null ? null : String.valueOf(decision.get("pid")),
                    decision == null ? null : String.valueOf(decision.get(PREFIX + "decision_mode")));
        }).toList();
        return ApiResponse.success(new Page(records, versions.getTotal(), page, pageSize));
    }
    private static QueryCondition eq(String field, Object value) {
        return QueryCondition.builder().fieldName(field).operator(QueryCondition.Operator.EQ).value(value).build();
    }
}
