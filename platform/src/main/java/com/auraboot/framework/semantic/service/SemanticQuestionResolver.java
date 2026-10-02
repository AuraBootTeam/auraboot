package com.auraboot.framework.semantic.service;

import com.auraboot.framework.semantic.compiler.SemanticQueryRequest;
import com.auraboot.framework.semantic.compiler.UserContext;
import com.auraboot.framework.semantic.dto.SemanticMetaResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Deterministic offline question resolver for the ChatBI eval harness (R5).
 *
 * <p>Matches the question text against the PUBLISHED catalog — metric labels
 * (zh/en), metric codes, and dimension labels — and produces the governed
 * {@link SemanticQueryRequest} the ChatBI skill would execute. This is the
 * offline tier of the R5 evaluation harness: it scores resolution accuracy
 * without any LLM provider. When real credentials unlock the LLM tier, the
 * provider-backed resolver implements the same interface and this class
 * remains the deterministic baseline the LLM answers are scored against.
 *
 * <p>Question authoring contract: the question must contain the metric's
 * zh label, en label, or code segment (case-insensitive). Questions that
 * match no metric resolve to empty — the harness scores them as unresolved,
 * never as a guess.
 */
@Service
@RequiredArgsConstructor
public class SemanticQuestionResolver {

    private final SemanticCatalogService catalogService;

    public Optional<SemanticQueryRequest> resolve(String question, UserContext user) {
        SemanticMetaResponse catalog = catalogService.listCatalog(user.tenantId());
        String needle = question.toLowerCase(Locale.ROOT);

        // Best-scoring (metric code, model) across all published models.
        String bestModelCode = null;
        String bestMetricCode = null;
        int bestScore = 0;

        for (SemanticMetaResponse.ModelMeta model : catalog.getModels()) {
            for (SemanticMetaResponse.MetricMeta metric : model.getMetrics()) {
                int score = matchScore(needle, metric);
                if (score > bestScore) {
                    bestScore = score;
                    bestModelCode = model.getCode();
                    bestMetricCode = metric.getCode();
                }
            }
        }
        if (bestMetricCode == null) return Optional.empty();

        SemanticQueryRequest request = new SemanticQueryRequest();
        request.setMetrics(List.of(bestModelCode + "." + bestMetricCode));

        // Dimensions whose zh/en label or code appears in the question.
        List<String> dims = new ArrayList<>();
        for (SemanticMetaResponse.ModelMeta model : catalog.getModels()) {
            for (var dim : model.getDimensions() == null ? List.<SemanticMetaResponse.DimensionMeta>of() : model.getDimensions()) {
                if (mentions(needle, dim.getLabel(), dim.getCode())) {
                    dims.add(model.getCode() + "." + dim.getCode());
                }
            }
        }
        request.setDimensions(dims);
        request.setLimit(100);
        return Optional.of(request);
    }

    private int matchScore(String needle, SemanticMetaResponse.MetricMeta metric) {
        int score = 0;
        if (mentions(needle, metric.getLabel(), metric.getCode())) score += 2;
        return score;
    }

    private boolean mentions(String needle, Map<String, String> label, String code) {
        if (label != null) {
            for (String value : label.values()) {
                if (value != null && needle.contains(value.toLowerCase(Locale.ROOT))) return true;
            }
        }
        return code != null && needle.contains(code.toLowerCase(Locale.ROOT));
    }
}
