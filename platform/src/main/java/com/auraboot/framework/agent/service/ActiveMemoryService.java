package com.auraboot.framework.agent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Active Memory pre-recall (memory-lifecycle.md §4 / ACP-Ideal §6.5).
 *
 * Called by {@link GroundingService} before emitting the BIF so that the
 * resulting intent frame carries a {@code preContext} payload of relevant
 * memory snippets. Downstream, {@code AuraBotChatService} injects these
 * snippets into the system prompt so the LLM sees user preferences / prior
 * decisions without a round-trip tool call.
 *
 * Two retrieval strategies are combined:
 *   1. keyword search against the current user message (high precision)
 *   2. importance-ordered recall of top-N user/tenant/global memories
 *      (broad prior-context, deduplicated with 1)
 *
 * All lookups go through {@link AgentMemoryService#searchScoped} /
 * {@link AgentMemoryService#loadScopedByImportance}, which enforce the
 * user/tenant/global visibility contract from PR-13.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActiveMemoryService {

    /** Agent code used to bucket AuraBot memories in ab_agent_memory. */
    private static final String AURABOT_AGENT = "aurabot";

    /** Hard cap on snippets returned to Grounding — keep prompt size bounded. */
    private static final int MAX_SNIPPETS = 8;
    /** Keyword hits weighted more heavily than importance recall. */
    private static final int KEYWORD_LIMIT = 5;
    private static final int IMPORTANCE_LIMIT = 5;

    private final AgentMemoryService memoryService;

    /**
     * Pre-recall memories visible to (tenantId, userId). Non-null, possibly empty.
     * Each snippet is a small map — pid / memory_type / memory_title / memory_content /
     * importance / scope — safe to serialize into BIF.preContext JSONB and stringify
     * into the system prompt.
     *
     * @param tenantId     current tenant
     * @param userId       current user (stringified); can be null if the caller has
     *                     no user context (rare — only cron / system runs)
     * @param userMessage  the natural-language input; drives keyword search
     */
    public List<Map<String, Object>> preRecall(Long tenantId, String userId, String userMessage) {
        if (tenantId == null) return List.of();

        List<Map<String, Object>> snippets = new ArrayList<>();
        java.util.Set<String> seenPids = new java.util.HashSet<>();

        try {
            if (userMessage != null && !userMessage.isBlank()) {
                for (Map<String, Object> row : memoryService.searchScoped(
                        tenantId, userId, AURABOT_AGENT, userMessage.trim(), KEYWORD_LIMIT)) {
                    if (seenPids.add(String.valueOf(row.get("pid")))) {
                        snippets.add(snippet(row));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("keyword preRecall failed for tenant={}: {}", tenantId, e.getMessage());
        }

        try {
            if (snippets.size() < MAX_SNIPPETS) {
                int remaining = MAX_SNIPPETS - snippets.size();
                for (Map<String, Object> row : memoryService.loadScopedByImportance(
                        tenantId, userId, AURABOT_AGENT, Math.max(IMPORTANCE_LIMIT, remaining))) {
                    if (snippets.size() >= MAX_SNIPPETS) break;
                    if (seenPids.add(String.valueOf(row.get("pid")))) {
                        snippets.add(snippet(row));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("importance preRecall failed for tenant={}: {}", tenantId, e.getMessage());
        }

        log.debug("Active Memory pre-recall: tenant={} user={} keyword='{}' → {} snippets",
                tenantId, userId, userMessage, snippets.size());
        return snippets;
    }

    /**
     * Compact a raw memory row into the snippet shape we persist + show the LLM.
     * Keeps only the fields useful for downstream consumers; truncates content to
     * 400 chars to keep BIF payload small.
     */
    private Map<String, Object> snippet(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pid", row.get("pid"));
        m.put("type", row.get("memory_type"));
        m.put("title", row.get("memory_title"));
        Object content = row.get("memory_content");
        if (content instanceof String s && s.length() > 400) {
            m.put("content", s.substring(0, 400) + "…");
        } else {
            m.put("content", content);
        }
        m.put("importance", row.get("importance"));
        m.put("scope", row.get("scope"));
        return m;
    }
}
