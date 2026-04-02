package com.auraboot.framework.agent.service;

import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * D1 Grounding: Semantic Term Resolver.
 * Maps natural language terms (like "active customers", "recent") to structured query conditions.
 * Reads from ab_semantic_term table.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticTermResolver {

    private final DynamicDataMapper dynamicDataMapper;
    private final ObjectMapper objectMapper;

    // Cache: term -> ResolvedTerm
    private final ConcurrentHashMap<String, ResolvedTerm> termCache = new ConcurrentHashMap<>();
    private volatile boolean cacheLoaded = false;

    public List<ResolvedTerm> resolve(Long tenantId, String userMessage, String modelCode) {
        ensureCacheLoaded();

        List<ResolvedTerm> results = new ArrayList<>();
        String lower = userMessage.toLowerCase();

        for (var entry : termCache.entrySet()) {
            String term = entry.getKey();
            ResolvedTerm def = entry.getValue();

            // Model scope check
            if (def.getModelCode() != null && !def.getModelCode().equals(modelCode)) continue;

            if (lower.contains(term.toLowerCase())) {
                results.add(def);
            }
        }

        // Sort by priority (higher first)
        results.sort(Comparator.comparingInt(r -> -r.getPriority()));
        return results;
    }

    private void ensureCacheLoaded() {
        if (cacheLoaded) return;
        synchronized (this) {
            if (cacheLoaded) return;
            loadCache();
            cacheLoaded = true;
        }
    }

    @SuppressWarnings("unchecked")
    private void loadCache() {
        try {
            String sql = "SELECT term, model_code, term_type, resolution, description, priority " +
                    "FROM ab_semantic_term WHERE tenant_id = -1";
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQueryWithoutTenant(sql, Map.of());
            for (Map<String, Object> row : rows) {
                String term = (String) row.get("term");
                Map<String, Object> resolution;
                Object resObj = row.get("resolution");
                if (resObj instanceof String s) {
                    resolution = objectMapper.readValue(s, Map.class);
                } else if (resObj instanceof Map<?, ?> m) {
                    resolution = (Map<String, Object>) m;
                } else {
                    continue;
                }

                termCache.put(term, new ResolvedTerm(
                        term,
                        (String) row.get("term_type"),
                        (String) row.get("model_code"),
                        resolution,
                        row.get("priority") != null ? ((Number) row.get("priority")).intValue() : 0
                ));
            }
            log.info("SemanticTermResolver loaded {} terms", termCache.size());
        } catch (Exception e) {
            log.warn("Failed to load semantic terms: {}", e.getMessage());
        }
    }

    @Data
    @AllArgsConstructor
    public static class ResolvedTerm {
        private String term;
        private String termType;    // filter | time_range | metric | segment
        private String modelCode;   // null = cross-model
        private Map<String, Object> resolution;
        private int priority;
    }
}
