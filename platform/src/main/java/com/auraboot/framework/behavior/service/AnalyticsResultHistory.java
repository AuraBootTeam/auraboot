package com.auraboot.framework.behavior.service;

import com.auraboot.framework.agent.dto.ResultContract;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.AggregateQueryRequest;
import com.auraboot.framework.meta.service.AggregateQueryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persist query references, then resolve history using the current viewer's permissions. */
@Service
@RequiredArgsConstructor
public class AnalyticsResultHistory {
    private final AggregateQueryService queries;
    private final ObjectMapper json;

    public static List<Map<String, Object>> references(List<ResultContract> contracts) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ResultContract contract : contracts) {
            if (!"aurabot:chat-bi".equals(contract.getSkillCode()) || !"success".equals(contract.getStatus())
                    || contract.getData() == null) continue;
            Object nested = contract.getData().get("data");
            Map<?, ?> data = nested instanceof Map<?, ?> map ? map : contract.getData();
            if (!(data.get("analysisId") instanceof String analysisId)
                    || !(data.get("dataSource") instanceof Map<?, ?> query)) continue;
            Map<String, Object> reference = new LinkedHashMap<>();
            reference.put("analysisId", analysisId);
            reference.put("dataSource", query);
            reference.put("chartType", data.getOrDefault("chartType", null));
            result.add(reference);
        }
        return List.copyOf(result);
    }

    public List<ResultContract> restore(JsonNode references) {
        if (references == null || references.isMissingNode()) return List.of();
        if (!references.isArray()) throw new IllegalStateException("Invalid persisted analytics references");
        List<ResultContract> contracts = new ArrayList<>();
        for (JsonNode reference : references) {
            AggregateQueryRequest query = json.convertValue(reference.path("dataSource"), AggregateQueryRequest.class);
            try {
                var response = queries.execute(query);
                if (response.getRows() == null) throw new IllegalStateException("Missing aggregate result rows");
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("analysisId", reference.path("analysisId").asText());
                data.put("dataSource", query);
                data.put("modelCode", query.getModelCode());
                data.put("chartType", reference.path("chartType").asText("table"));
                data.put("records", response.getRows());
                data.put("columns", response.getRows().isEmpty() ? List.of() : new ArrayList<>(response.getRows().get(0).keySet()));
                data.put("total", response.getRows().size());
                data.put("dimensions", query.getDimensions());
                data.put("metrics", query.getMetrics());
                data.put("historyRefreshed", true);
                contracts.add(ResultContract.builder().skillCode("aurabot:chat-bi").status("success")
                        .outputType("structured_result").renderHint("card").actionability("read_only").data(data).build());
            } catch (BusinessException denied) {
                // Do not expose query filters, old rows or exception details after access changes.
                contracts.add(ResultContract.builder().skillCode("aurabot:chat-bi").status("failed")
                        .outputType("text").renderHint("summary").actionability("read_only")
                        .textSummary("Historical analysis is unavailable. Check current data access and query configuration.").build());
            }
        }
        return List.copyOf(contracts);
    }
}
