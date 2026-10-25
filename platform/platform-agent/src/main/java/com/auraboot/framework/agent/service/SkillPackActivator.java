package com.auraboot.framework.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SkillPack Activation Filter (ACP-Ideal §3.3). Narrows the set of skill
 * codes the planner hands to the LLM so the model doesn't get a full
 * catalogue (which leads to expensive wrong turns).
 *
 * Tier 1 (this PR): activated-packs whitelist.
 *   For a given (tenantId, profileId, channel, runKind), look up all active
 *   {@link #filter} pack bindings whose dimensions match (NULL = any).
 *   Union of their skill_codes JSONB arrays = activation set.
 *
 *   Request's candidate list is intersected with the activation set. A
 *   candidate that isn't in any activated pack is dropped.
 *
 * Tier 2 (future): BIF-driven rules (e.g. skill requires actionability >=
 *   execute → filter out when BIF says read_only).
 *
 * Tier 3 (future): LLM top-K re-rank.
 *
 * Progressive rollout: tenants with ZERO bindings bypass the filter
 * (return candidates unchanged). Adding the first binding starts enforcing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillPackActivator {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Data
    @Builder
    public static class ActivationResult {
        private List<String> activatedCandidates;
        private int removedCount;
        private String reason;     // 'no_bindings_configured' | 'filter_applied'
    }

    public ActivationResult filter(Long tenantId, String profileId, String channel,
                                    String runKind, List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return ActivationResult.builder()
                    .activatedCandidates(List.of())
                    .removedCount(0).reason("empty_input").build();
        }

        List<Map<String, Object>> bindings = loadMatchingBindings(tenantId, profileId, channel, runKind);
        if (bindings.isEmpty()) {
            // Progressive rollout — no bindings means no filter yet.
            return ActivationResult.builder()
                    .activatedCandidates(candidates)
                    .removedCount(0).reason("no_bindings_configured").build();
        }

        Set<String> allowed = resolveSkillCodes(bindings);
        List<String> filtered = new java.util.ArrayList<>(candidates.size());
        int removed = 0;
        for (String c : candidates) {
            if (allowed.contains(c)) {
                filtered.add(c);
            } else {
                removed++;
                log.debug("SkillPack filter: dropped skill '{}' (not in activation set for tenant={}/{}/{}/{})",
                        c, tenantId, profileId, channel, runKind);
            }
        }
        return ActivationResult.builder()
                .activatedCandidates(filtered)
                .removedCount(removed).reason("filter_applied").build();
    }

    // =========================================================================

    private List<Map<String, Object>> loadMatchingBindings(Long tenantId, String profileId,
                                                            String channel, String runKind) {
        // Match rule semantics (same as Tool ACL): NULL on a dimension = match-any.
        StringBuilder where = new StringBuilder(
                "WHERE b.tenant_id = ? AND b.active = TRUE AND p.active = TRUE AND p.tenant_id = b.tenant_id ");
        List<Object> params = new java.util.ArrayList<>();
        params.add(tenantId);

        where.append("AND (b.profile_id IS NULL OR b.profile_id = ?) ");
        params.add(profileId);
        where.append("AND (b.channel    IS NULL OR b.channel    = ?) ");
        params.add(channel);
        where.append("AND (b.run_kind   IS NULL OR b.run_kind   = ?) ");
        params.add(runKind);

        String sql =
                "SELECT p.pid AS pack_pid, p.pack_code, p.skill_codes::text AS skill_codes_json " +
                        "FROM ab_agent_skill_pack_binding b " +
                        "JOIN ab_agent_skill_pack p ON p.pid = b.pack_pid " +
                        where + " ORDER BY b.priority DESC, b.id ASC";
        return jdbcTemplate.queryForList(sql, params.toArray());
    }

    private Set<String> resolveSkillCodes(List<Map<String, Object>> bindings) {
        Set<String> out = new LinkedHashSet<>();
        for (Map<String, Object> b : bindings) {
            String json = (String) b.get("skill_codes_json");
            try {
                List<String> codes = objectMapper.readValue(json, new TypeReference<>() {});
                if (codes != null) out.addAll(codes);
            } catch (Exception e) {
                log.warn("SkillPack {} has malformed skill_codes JSON: {}", b.get("pack_code"), e.getMessage());
            }
        }
        return Collections.unmodifiableSet(out);
    }
}
