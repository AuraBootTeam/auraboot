package com.auraboot.framework.aisearch.service;

import com.auraboot.framework.aisearch.dto.GlobalSearchCandidates;
import com.auraboot.framework.aisearch.dto.GlobalSearchResult;
import com.auraboot.framework.aisearch.dto.GlobalSearchPreference;
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
     * Search configured readable models, grouped by model.
     *
     * @param userId        caller user id; must be resolvable (fail fast otherwise)
     * @param tenantId      caller tenant id; used to scope search preferences
     * @param keyword       non-blank search keyword
     * @param perModelLimit max records returned per model (bounded)
     * @param maxModels     max groups returned (bounded)
     * @return grouped hits from readable ∩ enabled models, in preference order
     */
    GlobalSearchResult search(
            Long userId, Long tenantId, String keyword, Integer perModelLimit, Integer maxModels);

    /**
     * Readable model candidates with the caller's current personal selection.
     * A missing selection reports all candidates enabled for slice-1 compatibility.
     */
    GlobalSearchCandidates listCandidates(Long userId, Long tenantId);

    /** Return the caller's stored preference, or an unconfigured default. */
    GlobalSearchPreference getSearchPreference(Long userId, Long tenantId);

    /**
     * Store an ordered enabled-model selection after normalizing it to the live
     * readable set. Unknown or unreadable model codes are rejected.
     */
    GlobalSearchPreference saveSearchPreference(Long userId, Long tenantId, List<String> modelCodes);

    /**
     * Business models of the current tenant the given user may read
     * (non view/meta types, read permission enforced). Shared with the AI
     * search keyword fallback so every cross-model search path converges on
     * the same permission boundary.
     */
    List<Model> readableCandidateModels(Long userId);
}
