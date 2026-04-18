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

    /** Default agent code when caller doesn't specify one (built-in chat path). */
    public static final String DEFAULT_AGENT = "aurabot";

    /**
     * Prefix annotation prepended to {@code memory_content} when the source
     * memory row has {@code shadow_mode=TRUE} (plan §8). Bilingual marker so
     * downstream LLM (AuraBot) understands uncertainty and prefixes its reply
     * with "根据团队近期记忆（尚在观察期）：...". Keep the literal in sync
     * with the E2E / backend tests — callers may assert on this string.
     */
    public static final String SHADOW_ANNOTATION_PREFIX =
            "[SHADOW / 近期团队记忆 · 观察中] ";

    /** Hard cap on snippets returned to Grounding — keep prompt size bounded. */
    private static final int MAX_SNIPPETS = 8;
    /** Keyword hits weighted more heavily than importance recall. */
    private static final int KEYWORD_LIMIT = 5;
    private static final int IMPORTANCE_LIMIT = 5;

    private final AgentMemoryService memoryService;

    /**
     * Pre-recall memories visible to (tenantId, userId) from {@code agentCode}'s
     * memory bucket. Non-null, possibly empty. Each snippet is a small map —
     * pid / memory_type / memory_title / memory_content / importance / scope —
     * safe to serialize into BIF.preContext JSONB and stringify into the system
     * prompt.
     *
     * Exceptions are NOT swallowed here — {@code GroundingService} wraps the
     * whole call so a storage error surfaces there (single catch point;
     * "no silent fallback" red-line).
     *
     * @param tenantId     current tenant
     * @param userId       current user (stringified); can be null if the caller has
     *                     no user context (rare — only cron / system runs)
     * @param agentCode    agent bucket ({@link #DEFAULT_AGENT} when null)
     * @param userMessage  the natural-language input; drives keyword search
     */
    public List<Map<String, Object>> preRecall(Long tenantId, String userId,
                                                String agentCode, String userMessage) {
        if (tenantId == null) return List.of();
        String agent = (agentCode == null || agentCode.isBlank()) ? DEFAULT_AGENT : agentCode;

        List<Map<String, Object>> snippets = new ArrayList<>();
        java.util.Set<String> seenPids = new java.util.HashSet<>();

        if (userMessage != null && !userMessage.isBlank()) {
            for (Map<String, Object> row : memoryService.searchScoped(
                    tenantId, userId, agent, userMessage.trim(), KEYWORD_LIMIT)) {
                if (seenPids.add(String.valueOf(row.get("pid")))) {
                    snippets.add(snippet(row));
                }
            }
        }

        if (snippets.size() < MAX_SNIPPETS) {
            // Intentionally fetch at least IMPORTANCE_LIMIT rows even when there's
            // less room, so the importance pass always runs and we get fresh
            // high-importance memories even if keyword search returned noise.
            int fetch = Math.max(IMPORTANCE_LIMIT, MAX_SNIPPETS - snippets.size());
            for (Map<String, Object> row : memoryService.loadScopedByImportance(
                    tenantId, userId, agent, fetch)) {
                if (snippets.size() >= MAX_SNIPPETS) break;
                if (seenPids.add(String.valueOf(row.get("pid")))) {
                    snippets.add(snippet(row));
                }
            }
        }

        log.debug("Active Memory pre-recall: tenant={} user={} agent={} keyword='{}' → {} snippets",
                tenantId, userId, agent, userMessage, snippets.size());
        return snippets;
    }

    /** Backward-compat: call with default "aurabot" agent. */
    public List<Map<String, Object>> preRecall(Long tenantId, String userId, String userMessage) {
        return preRecall(tenantId, userId, DEFAULT_AGENT, userMessage);
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
        String contentStr = content == null ? null : content.toString();
        if (contentStr != null && contentStr.length() > 400) {
            contentStr = contentStr.substring(0, 400) + "…";
        }
        // Shadow-mode annotation (plan §8): tenant memories still in the
        // 7-day observation window are prefixed so AuraBot can preface its
        // reply with uncertainty language and frontend can render a
        // "这条不对" retract button keyed on the marker.
        Object shadow = row.get("shadow_mode");
        boolean isShadow = shadow instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(shadow));
        if (isShadow && contentStr != null) {
            contentStr = SHADOW_ANNOTATION_PREFIX + contentStr;
        }
        m.put("content", contentStr);
        m.put("importance", row.get("importance"));
        m.put("scope", row.get("scope"));
        m.put("shadow_mode", isShadow);
        return m;
    }
}
