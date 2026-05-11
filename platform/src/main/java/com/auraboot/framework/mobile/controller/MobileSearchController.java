package com.auraboot.framework.mobile.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.mobile.dto.MobileSearchResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/mobile/search")
@RequiredArgsConstructor
public class MobileSearchController {

    private static final int MAX_HITS_PER_MODEL = 3;
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final MetaModelService metaModelService;
    private final DynamicDataService dynamicDataService;

    @GetMapping
    public ApiResponse<MobileSearchResult> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false, name = "q") String query,
            @RequestParam(required = false) String models,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {

        String effectiveKeyword = firstNonBlank(keyword, query);
        if (effectiveKeyword == null) {
            return ApiResponse.success(emptyResult(""));
        }

        int effectiveLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        List<String> targetModels = resolveTargetModels(models);
        if (targetModels.isEmpty()) {
            return ApiResponse.success(emptyResult(effectiveKeyword));
        }

        List<MobileSearchResult.SearchHit> hits = new ArrayList<>();
        for (String modelCode : targetModels) {
            if (hits.size() >= effectiveLimit) break;
            hits.addAll(searchModel(modelCode, effectiveKeyword, effectiveLimit - hits.size()));
        }

        return ApiResponse.success(MobileSearchResult.builder()
                .keyword(effectiveKeyword)
                .totalCount(hits.size())
                .hits(hits)
                .build());
    }

    private MobileSearchResult emptyResult(String keyword) {
        return MobileSearchResult.builder()
                .keyword(keyword)
                .totalCount(0)
                .hits(List.of())
                .build();
    }

    private List<String> resolveTargetModels(String modelsCsv) {
        if (modelsCsv != null && !modelsCsv.isBlank()) {
            return Arrays.stream(modelsCsv.split(","))
                    .map(String::trim)
                    .filter(model -> !model.isBlank())
                    .toList();
        }

        PageResult<MetaModelDTO> pageResult = metaModelService.searchModels(
                1, 100, null, null, null, "entity", "published", null, null, null, true);
        if (pageResult == null || pageResult.getRecords() == null) {
            return List.of();
        }
        return pageResult.getRecords().stream()
                .map(MetaModelDTO::getCode)
                .filter(code -> code != null && !code.isBlank())
                .toList();
    }

    private List<MobileSearchResult.SearchHit> searchModel(String modelCode, String keyword, int remainingLimit) {
        if (remainingLimit <= 0) return List.of();
        DynamicQueryRequest request = DynamicQueryRequest.builder()
                .pageNum(1)
                .pageSize(Math.min(MAX_HITS_PER_MODEL, remainingLimit))
                .keyword(keyword)
                .build();

        PaginationResult<Map<String, Object>> page = dynamicDataService.list(modelCode, request);
        if (page == null || page.getRecords() == null || page.getRecords().isEmpty()) {
            return List.of();
        }

        String modelLabel = resolveModelLabel(modelCode);
        return page.getRecords().stream()
                .map(record -> toHit(modelCode, modelLabel, record))
                .toList();
    }

    private String resolveModelLabel(String modelCode) {
        return metaModelService.getModelDefinition(modelCode)
                .map(def -> def.getDisplayName() != null ? def.getDisplayName() : modelCode)
                .orElse(modelCode);
    }

    private MobileSearchResult.SearchHit toHit(String modelCode, String modelLabel, Map<String, Object> record) {
        String recordId = stringify(firstPresent(record, "id", "recordId"));
        String recordPid = stringify(firstPresent(record, "pid", "recordPid"));
        String displayName = resolveDisplayName(record, recordPid, recordId);

        Map<String, String> fields = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : record.entrySet()) {
            if (fields.size() >= 3) break;
            String key = entry.getKey();
            if (isSystemField(key)) continue;
            String value = stringify(entry.getValue());
            if (value != null && !value.isBlank() && !value.equals(displayName)) {
                fields.put(key, value);
            }
        }

        return MobileSearchResult.SearchHit.builder()
                .modelCode(modelCode)
                .modelLabel(modelLabel)
                .recordId(recordId != null ? recordId : recordPid)
                .recordPid(recordPid)
                .displayName(displayName)
                .title(displayName)
                .type("record")
                .deepLink(recordId != null ? "auraboot://object/" + modelCode + "/" + recordId : null)
                .fields(fields)
                .build();
    }

    private String resolveDisplayName(Map<String, Object> record, String recordPid, String recordId) {
        for (String key : List.of("displayName", "name", "title", "label")) {
            String value = stringify(record.get(key));
            if (value != null && !value.isBlank()) return value;
        }
        for (Map.Entry<String, Object> entry : record.entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            if (key.endsWith("_name") || key.endsWith("_title") || key.endsWith("_no") || key.endsWith("_code")) {
                String value = stringify(entry.getValue());
                if (value != null && !value.isBlank()) return value;
            }
        }
        if (recordPid != null && !recordPid.isBlank()) return recordPid;
        if (recordId != null && !recordId.isBlank()) return recordId;
        return "Record";
    }

    private Object firstPresent(Map<String, Object> record, String... keys) {
        for (String key : keys) {
            if (record.containsKey(key)) return record.get(key);
        }
        return null;
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first.trim();
        if (second != null && !second.isBlank()) return second.trim();
        return null;
    }

    private String stringify(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean isSystemField(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.equals("id")
                || normalized.equals("pid")
                || normalized.equals("tenant_id")
                || normalized.equals("created_at")
                || normalized.equals("updated_at")
                || normalized.equals("deleted");
    }
}
