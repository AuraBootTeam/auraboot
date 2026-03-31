package com.auraboot.framework.meta.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.dto.NamedQueryTestRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.service.NamedQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Batch query controller for executing multiple datasource queries in a single request.
 * Reduces HTTP round-trips for dashboard pages that fire 5-10 independent queries.
 */
@Slf4j
@RestController
@RequestMapping("/api/datasource")
@RequiredArgsConstructor
@Tag(name = "Batch Query", description = "Execute multiple datasource queries in parallel")
public class BatchQueryController {

    private final NamedQueryService namedQueryService;

    private static final int MAX_QUERIES_PER_BATCH = 20;
    private static final int DEFAULT_MAX_ITEMS = 200;
    private static final int ABSOLUTE_MAX_ITEMS = 1000;

    @PostMapping("/batch")
    @Operation(summary = "Batch query", description = "Execute multiple NamedQuery datasource queries in parallel. Each query item must specify a unique id, a datasourceId in nq:{queryCode} format, and optional params.")
    public ApiResponse<Map<String, Object>> batchQuery(@RequestBody BatchRequest request) {
        if (request.getQueries() == null || request.getQueries().isEmpty()) {
            return ApiResponse.success(Collections.emptyMap());
        }

        if (request.getQueries().size() > MAX_QUERIES_PER_BATCH) {
            return ApiResponse.error("Batch query limit exceeded: max " + MAX_QUERIES_PER_BATCH + " queries per request");
        }

        List<CompletableFuture<Map.Entry<String, Object>>> futures = request.getQueries().stream()
                .map(q -> CompletableFuture.supplyAsync(() -> {
                    try {
                        Object result = executeQuery(q);
                        return Map.entry(q.getId(), result);
                    } catch (Exception e) {
                        // CATCH: non-transactional parallel query — isolate individual query failures
                        log.warn("Batch query item '{}' failed: {}", q.getId(), e.getMessage());
                        return Map.entry(q.getId(), (Object) Map.of("error", e.getMessage()));
                    }
                }))
                .collect(Collectors.toList());

        Map<String, Object> results = futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        return ApiResponse.success(results);
    }

    private Object executeQuery(QueryItem q) {
        String datasourceId = q.getDatasourceId();
        if (datasourceId == null || !datasourceId.startsWith("nq:")) {
            throw new IllegalArgumentException("Batch query only supports nq:{queryCode} datasource format, got: " + datasourceId);
        }

        String queryCode = datasourceId.substring(3);
        Map<String, Object> params = q.getParams() != null ? q.getParams() : Collections.emptyMap();

        int maxItems = DEFAULT_MAX_ITEMS;
        if (params.containsKey("maxItems")) {
            try {
                maxItems = Integer.parseInt(params.get("maxItems").toString());
            } catch (NumberFormatException ignored) {
            }
        }

        NamedQueryTestRequest nqRequest = new NamedQueryTestRequest();
        nqRequest.setPage(1);
        nqRequest.setSize(Math.min(maxItems, ABSOLUTE_MAX_ITEMS));
        nqRequest.setExecuteQuery(true);
        nqRequest.setParameters(filterControlParams(params));

        PaginationResult<Map<String, Object>> result = namedQueryService.executeQuery(queryCode, nqRequest);
        return result;
    }

    private static final Set<String> CONTROL_PARAMS = Set.of(
            "datasourceId", "format", "maxItems", "valueField", "labelField",
            "searchField", "keyword", "page", "size", "reportingCurrency");

    private Map<String, Object> filterControlParams(Map<String, Object> params) {
        Map<String, Object> nqParams = new HashMap<>();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (!CONTROL_PARAMS.contains(entry.getKey())) {
                nqParams.put(entry.getKey(), entry.getValue());
            }
        }
        return nqParams;
    }

    @Data
    public static class BatchRequest {
        private List<QueryItem> queries;
    }

    @Data
    public static class QueryItem {
        /** Unique identifier for this query within the batch — used as the key in the response map */
        private String id;
        /** Datasource identifier in nq:{queryCode} format */
        private String datasourceId;
        /** Optional parameters passed to the NamedQuery */
        private Map<String, Object> params;
    }
}
