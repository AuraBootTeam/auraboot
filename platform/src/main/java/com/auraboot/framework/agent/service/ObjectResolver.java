package com.auraboot.framework.agent.service;

import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * D1 Grounding: Object Resolver — maps natural language to model_code.
 * Uses an inverted index built from i18n labels + ab_object_alias.
 * Three-layer matching: exact/alias → fuzzy (contains) → embedding similarity (pgvector).
 * TTL-cached model index (5-min) to avoid per-call DB queries.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ObjectResolver {

    private final DynamicDataMapper dynamicDataMapper;

    // Optional embedding-assisted matching for deployments that enable ModelEmbeddingService
    @Autowired(required = false)
    private ModelEmbeddingService modelEmbeddingService;

    private static final long CACHE_TTL_MS = 5 * 60 * 1000L; // 5 minutes

    // Platform-level inverted index: {alias_text → model_code} — rebuilt on startup
    private final ConcurrentHashMap<String, String> invertedIndex = new ConcurrentHashMap<>();

    // Display name index: {model_code → display_name} — for fuzzy matching
    private final ConcurrentHashMap<String, String> displayNameIndex = new ConcurrentHashMap<>();

    // Tenant-specific cache with TTL
    private final Map<Long, CachedIndex> tenantCache = new ConcurrentHashMap<>();

    /**
     * Cached tenant index with TTL expiration.
     */
    private record CachedIndex(
            Map<String, String> invertedIndex,
            Map<String, String> displayNames,
            Instant expiresAt
    ) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    @PostConstruct
    public void buildIndex() {
        rebuildIndex();
    }

    public void rebuildIndex() {
        invertedIndex.clear();
        displayNameIndex.clear();
        tenantCache.clear();
        int count = 0;

        // Layer 1: Load from ab_object_alias (tenant_id = -1 for platform built-in)
        try {
            String sql = "SELECT alias, model_code FROM ab_object_alias WHERE tenant_id = -1 ORDER BY priority DESC";
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQueryWithoutTenant(sql, Map.of());
            for (Map<String, Object> row : rows) {
                String alias = (String) row.get("alias");
                String modelCode = (String) row.get("model_code");
                if (alias != null && modelCode != null) {
                    invertedIndex.putIfAbsent(alias.toLowerCase(), modelCode);
                    count++;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load object aliases: {}", e.getMessage());
        }

        // Layer 2: Load model display names from ab_meta_model.extension->>'displayName'
        try {
            String sql = "SELECT code, extension->>'displayName' as display_name FROM ab_meta_model " +
                    "WHERE status = 'published' AND (deleted_flag = FALSE OR deleted_flag IS NULL)";
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQueryWithoutTenant(sql, Map.of());
            for (Map<String, Object> row : rows) {
                String code = (String) row.get("code");
                String displayName = (String) row.get("display_name");
                if (code != null) {
                    invertedIndex.putIfAbsent(code.toLowerCase(), code);  // L1: exact model_code match
                    if (displayName != null && !displayName.isBlank()) {
                        invertedIndex.putIfAbsent(displayName.toLowerCase(), code);  // L2: display name
                        displayNameIndex.put(code, displayName.toLowerCase());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load model display names: {}", e.getMessage());
        }

        log.info("ObjectResolver index built: {} entries, {} display names", invertedIndex.size(), displayNameIndex.size());
    }

    /**
     * Resolve model_code from user message.
     * Three-layer strategy: exact match → alias match → fuzzy (contains) match on display names.
     * Returns the best match (longest match wins for ambiguity resolution).
     */
    public ObjectResult resolve(Long tenantId, String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return new ObjectResult(null, 0.0, "none", List.of());
        }

        String lower = userMessage.toLowerCase();

        // Load tenant-specific aliases with TTL cache
        loadTenantAliasesCached(tenantId);

        // Merge tenant cache into search scope
        Map<String, String> searchIndex = new HashMap<>(invertedIndex);
        Map<String, String> searchDisplayNames = new HashMap<>(displayNameIndex);
        CachedIndex cached = tenantCache.get(tenantId);
        if (cached != null) {
            searchIndex.putAll(cached.invertedIndex());
            searchDisplayNames.putAll(cached.displayNames());
        }

        // Phase 1: Find all exact/alias matches in the message (longest match wins)
        String bestAlias = null;
        String bestModelCode = null;
        double bestConfidence = 0.0;
        String bestMatchType = "none";

        for (Map.Entry<String, String> entry : searchIndex.entrySet()) {
            String alias = entry.getKey();
            if (lower.contains(alias)) {
                // Longer alias = more specific = higher confidence
                double confidence = alias.length() >= 3 ? 0.90 : 0.75;
                // Exact model_code match gets highest confidence
                if (alias.equals(entry.getValue())) confidence = 0.99;

                if (bestAlias == null || alias.length() > bestAlias.length()) {
                    bestAlias = alias;
                    bestModelCode = entry.getValue();
                    bestConfidence = confidence;
                    bestMatchType = alias.equals(entry.getValue()) ? "exact" : "alias";
                }
            }
        }

        if (bestModelCode != null) {
            return new ObjectResult(bestModelCode, bestConfidence, bestMatchType, List.of());
        }

        // Phase 2: Fuzzy matching — check if any display name partially contains/is contained by user input
        List<String> candidates = new ArrayList<>();
        for (Map.Entry<String, String> entry : searchDisplayNames.entrySet()) {
            String modelCode = entry.getKey();
            String displayName = entry.getValue();
            // Check if user message contains part of display name, or display name contains part of message words
            if (fuzzyContains(lower, displayName)) {
                candidates.add(modelCode);
            }
        }

        // Also check model codes for partial match (e.g., "account" matches "crm_account")
        for (Map.Entry<String, String> entry : searchIndex.entrySet()) {
            String alias = entry.getKey();
            String modelCode = entry.getValue();
            // Check if any word in user message is a substring of the alias or vice versa
            if (!candidates.contains(modelCode) && fuzzyContains(lower, alias)) {
                candidates.add(modelCode);
            }
        }

        if (candidates.size() == 1) {
            return new ObjectResult(candidates.get(0), 0.70, "fuzzy", candidates);
        } else if (!candidates.isEmpty()) {
            // Multiple candidates — lower confidence, let caller disambiguate
            return new ObjectResult(candidates.get(0), 0.50, "fuzzy", candidates);
        }

        // Phase 3: Embedding similarity (optional, if ModelEmbeddingService available)
        if (modelEmbeddingService != null) {
            try {
                List<String> embeddingMatches = modelEmbeddingService.findSimilarModels(tenantId, lower, 3);
                if (embeddingMatches.size() == 1) {
                    return new ObjectResult(embeddingMatches.get(0), 0.75, "embedding", embeddingMatches);
                } else if (!embeddingMatches.isEmpty()) {
                    return new ObjectResult(embeddingMatches.get(0), 0.55, "embedding", embeddingMatches);
                }
            } catch (Exception e) {
                log.debug("Embedding fallback failed: {}", e.getMessage());
            }
        }

        return new ObjectResult(null, 0.0, "none", List.of());
    }

    /**
     * Fuzzy contains check: returns true if query and target share a common substring
     * of length >= 2. Handles CJK (no whitespace separation) and Latin text.
     * For Latin text, also checks underscore-split segments (e.g., "account" in "crm_account").
     */
    private boolean fuzzyContains(String query, String target) {
        int latinMinLen = 3; // Minimum segment length for Latin text to avoid false positives
        int cjkMinLen = 2;  // CJK characters carry more meaning per char

        // Check if any segment of target (split by _ or space) appears in query
        String[] targetSegments = target.split("[\\s_]+");
        for (String seg : targetSegments) {
            if (seg.length() >= latinMinLen && query.contains(seg)) {
                return true;
            }
        }
        // Check if any segment of query (split by common delimiters) appears in target
        String[] querySegments = query.split("[\\s,;.!?，。！？、_]+");
        for (String seg : querySegments) {
            if (seg.length() >= latinMinLen && target.contains(seg)) {
                return true;
            }
        }
        // CJK sliding window: check 2-char substrings of target against query
        // Only for CJK text (contains CJK ideographs)
        boolean isCjk = target.chars().anyMatch(c ->
                Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS);
        if (isCjk) {
            for (int i = 0; i <= target.length() - cjkMinLen; i++) {
                String sub = target.substring(i, i + cjkMinLen);
                if (query.contains(sub)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Resolve command code for a given model and intent.
     * Maps common intents to execution_config.type values.
     *
     * @param tenantId  tenant ID
     * @param modelCode target model code
     * @param intent    natural language intent: "create", "update", "delete", "query", "transition"
     * @return command code (e.g., "crm:create_account") or null if not found
     */
    public String resolveCommand(Long tenantId, String modelCode, String intent) {
        if (modelCode == null || intent == null) return null;

        String execType = mapIntentToExecType(intent.toLowerCase().trim());
        if (execType == null) {
            log.debug("No execution type mapping for intent: {}", intent);
            return null;
        }

        try {
            String sql = "SELECT code FROM ab_command_definition " +
                    "WHERE tenant_id = #{params.tenantId} " +
                    "AND model_code = #{params.modelCode} " +
                    "AND execution_config->>'type' = #{params.execType} " +
                    "AND is_current = true " +
                    "AND (deleted_flag = FALSE OR deleted_flag IS NULL) " +
                    "ORDER BY code ASC LIMIT 1";
            Map<String, Object> params = Map.of(
                    "tenantId", tenantId,
                    "modelCode", modelCode,
                    "execType", execType
            );
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQueryWithoutTenant(sql, params);
            if (!rows.isEmpty()) {
                return (String) rows.get(0).get("code");
            }
        } catch (Exception e) {
            log.warn("Failed to resolve command for model={}, intent={}: {}", modelCode, intent, e.getMessage());
        }

        return null;
    }

    /**
     * Map natural language intent to execution_config.type value.
     */
    private String mapIntentToExecType(String intent) {
        return switch (intent) {
            case "create", "add", "new" -> "create";
            case "update", "edit", "modify" -> "update";
            case "delete", "remove" -> "delete";
            case "transition", "approve", "reject", "submit", "activate", "archive" -> "state_transition";
            case "query", "list", "search", "find", "get" -> "query";
            default -> null;
        };
    }

    /**
     * Load tenant-specific aliases with TTL caching.
     * Replaces per-call DB query with 5-minute cached index.
     */
    private void loadTenantAliasesCached(Long tenantId) {
        if (tenantId == null || tenantId <= 0) return;

        CachedIndex cached = tenantCache.get(tenantId);
        if (cached != null && !cached.isExpired()) {
            return; // Cache hit, still valid
        }

        try {
            Map<String, String> tenantInverted = new HashMap<>();
            Map<String, String> tenantDisplayNames = new HashMap<>();

            // Load tenant-specific aliases
            String sql = "SELECT alias, model_code FROM ab_object_alias " +
                    "WHERE tenant_id = #{params.tenantId} ORDER BY priority DESC";
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql, Map.of("tenantId", tenantId));
            for (Map<String, Object> row : rows) {
                String alias = (String) row.get("alias");
                String modelCode = (String) row.get("model_code");
                if (alias != null && modelCode != null) {
                    tenantInverted.put(alias.toLowerCase(), modelCode);
                }
            }

            // Load tenant-specific model display names
            String modelSql = "SELECT code, extension->>'displayName' as display_name FROM ab_meta_model " +
                    "WHERE status = 'published' AND (deleted_flag = FALSE OR deleted_flag IS NULL)";
            List<Map<String, Object>> modelRows = dynamicDataMapper.selectByQuery(modelSql, Map.of());
            for (Map<String, Object> row : modelRows) {
                String code = (String) row.get("code");
                String displayName = (String) row.get("display_name");
                if (code != null && displayName != null && !displayName.isBlank()) {
                    tenantDisplayNames.put(code, displayName.toLowerCase());
                }
            }

            Instant expiresAt = Instant.now().plusMillis(CACHE_TTL_MS);
            tenantCache.put(tenantId, new CachedIndex(tenantInverted, tenantDisplayNames, expiresAt));
            log.debug("Tenant {} alias cache refreshed: {} aliases, {} display names",
                    tenantId, tenantInverted.size(), tenantDisplayNames.size());
        } catch (Exception e) {
            log.warn("Failed to load tenant aliases for tenantId={}: {}", tenantId, e.getMessage());
        }
    }

    /**
     * Invalidate cache for a specific tenant (e.g., after alias update).
     */
    public void invalidateCache(Long tenantId) {
        if (tenantId != null) {
            tenantCache.remove(tenantId);
        }
    }

    /**
     * Invalidate all caches and rebuild platform index.
     */
    public void invalidateAll() {
        tenantCache.clear();
        rebuildIndex();
    }

    @Data
    @AllArgsConstructor
    public static class ObjectResult {
        private String modelCode;
        private double confidence;
        private String matchType;
        private List<String> candidates;
    }
}
