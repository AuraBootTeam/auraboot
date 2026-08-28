package com.auraboot.framework.aisearch.service;

import com.auraboot.framework.aisearch.dto.GlobalSearchResult;
import com.auraboot.framework.meta.entity.Model;

import java.util.List;

/**
 * Unified cross-model global search.
 * <p>
 * Single server-side entry point for global record search. Candidate models
 * are converged to the models the caller is allowed to read
 * ({@code model.<code>.read}, fail-closed), so the response never reveals the
 * existence of records the caller cannot read.
 *
 * @author AuraBoot Team
 */
public interface GlobalSearchService {

    /**
     * Search across readable models, grouped by model.
     *
     * @param userId        caller user id; must be resolvable (fail fast otherwise)
     * @param keyword       non-blank search keyword
     * @param perModelLimit max records returned per model (bounded)
     * @param maxModels     max groups returned (bounded)
     * @return grouped hits; models without read permission are absent entirely
     */
    GlobalSearchResult search(Long userId, String keyword, Integer perModelLimit, Integer maxModels);

    /**
     * Business models of the current tenant the given user may read
     * (non view/meta types, read permission enforced). Shared with the AI
     * search keyword fallback so every cross-model search path converges on
     * the same permission boundary.
     */
    List<Model> readableCandidateModels(Long userId);
}
