package com.auraboot.framework.agent.service;

import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.rag.service.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages display_name embeddings for ab_meta_model records.
 * Provides semantic similarity search for ACP ObjectResolver fallback.
 * <p>
 * All methods handle missing embedding provider gracefully (log + return empty).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelEmbeddingService {

    private final EmbeddingService embeddingService;
    private final DynamicDataMapper dynamicDataMapper;

    private static final String DEFAULT_PROVIDER = "openai";
    private static final double SIMILARITY_THRESHOLD = 0.7;

    /**
     * Embed a model's display name and store the vector in ab_meta_model.display_name_embedding.
     *
     * @param tenantId    tenant ID
     * @param modelCode   model code
     * @param displayName display name text to embed
     * @return true if embedding was stored, false on failure or missing provider
     */
    public boolean embedModel(Long tenantId, String modelCode, String displayName) {
        if (displayName == null || displayName.isBlank()) {
            log.debug("Skipping embedding for model {} — empty display name", modelCode);
            return false;
        }

        try {
            float[] vector = embeddingService.embed(tenantId, displayName, DEFAULT_PROVIDER);
            if (vector == null) {
                log.warn("Embedding provider returned null for model {} — provider may not be configured", modelCode);
                return false;
            }

            String vectorLiteral = toVectorLiteral(vector);
            String sql = "UPDATE ab_meta_model SET display_name_embedding = '" + vectorLiteral + "'::vector " +
                    "WHERE tenant_id = #{params.tenantId} AND code = #{params.modelCode} " +
                    "AND is_current = true AND (deleted_flag = FALSE OR deleted_flag IS NULL)";
            dynamicDataMapper.selectByQueryWithoutTenant(sql, Map.of(
                    "tenantId", tenantId,
                    "modelCode", modelCode
            ));

            log.debug("Stored embedding for model {} (dim={})", modelCode, vector.length);
            return true;
        } catch (Exception e) {
            log.warn("Failed to embed model {}: {}", modelCode, e.getMessage());
            return false;
        }
    }

    /**
     * Find models with display names semantically similar to the query text.
     * Uses pgvector cosine distance operator ({@code <=>}).
     *
     * @param tenantId  tenant ID
     * @param queryText natural language query
     * @param topN      max results to return
     * @return list of model codes with similarity above threshold, ordered by similarity desc
     */
    public List<String> findSimilarModels(Long tenantId, String queryText, int topN) {
        if (queryText == null || queryText.isBlank()) {
            return List.of();
        }

        try {
            float[] queryVector = embeddingService.embed(tenantId, queryText, DEFAULT_PROVIDER);
            if (queryVector == null) {
                log.debug("Embedding provider not configured — skipping semantic search");
                return List.of();
            }

            String vectorLiteral = toVectorLiteral(queryVector);
            String sql = "SELECT code, 1 - (display_name_embedding <=> '" + vectorLiteral + "'::vector) AS similarity " +
                    "FROM ab_meta_model " +
                    "WHERE tenant_id = #{params.tenantId} " +
                    "AND is_current = true " +
                    "AND (deleted_flag = FALSE OR deleted_flag IS NULL) " +
                    "AND display_name_embedding IS NOT NULL " +
                    "ORDER BY display_name_embedding <=> '" + vectorLiteral + "'::vector " +
                    "LIMIT #{params.topN}";

            List<Map<String, Object>> rows = dynamicDataMapper.selectByQueryWithoutTenant(sql, Map.of(
                    "tenantId", tenantId,
                    "topN", topN
            ));

            return rows.stream()
                    .filter(row -> {
                        Object sim = row.get("similarity");
                        double similarity = sim instanceof Number n ? n.doubleValue() : 0.0;
                        return similarity >= SIMILARITY_THRESHOLD;
                    })
                    .map(row -> (String) row.get("code"))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Semantic model search failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Batch embed all published models that don't yet have an embedding.
     *
     * @param tenantId tenant ID
     * @return number of models embedded
     */
    public int embedAllModels(Long tenantId) {
        try {
            String sql = "SELECT code, extension->>'displayName' as display_name FROM ab_meta_model " +
                    "WHERE tenant_id = #{params.tenantId} " +
                    "AND is_current = true " +
                    "AND status = 'published' " +
                    "AND (deleted_flag = FALSE OR deleted_flag IS NULL) " +
                    "AND display_name_embedding IS NULL";
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQueryWithoutTenant(sql,
                    Map.of("tenantId", tenantId));

            int count = 0;
            for (Map<String, Object> row : rows) {
                String code = (String) row.get("code");
                String displayName = (String) row.get("display_name");
                if (displayName != null && !displayName.isBlank() && embedModel(tenantId, code, displayName)) {
                    count++;
                }
            }
            log.info("Batch embedded {} models for tenant {}", count, tenantId);
            return count;
        } catch (Exception e) {
            log.warn("Batch embedding failed for tenant {}: {}", tenantId, e.getMessage());
            return 0;
        }
    }

    /**
     * Convert float array to pgvector literal format: [0.1,0.2,0.3]
     */
    private String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        sb.append(']');
        return sb.toString();
    }
}
