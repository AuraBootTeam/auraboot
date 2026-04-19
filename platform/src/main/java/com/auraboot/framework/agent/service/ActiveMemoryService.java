package com.auraboot.framework.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
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
public class ActiveMemoryService {

    /**
     * Phase 4 (PR-85) — hard cap on L1 rows returned from {@link #recallL1Capped}
     * (and any future caller that pulls raw L1 for prompt assembly). Defaults
     * to 30 per design §9.2; configurable via {@code acp.memory.l1l2.max-l1}.
     * Disabled-by-default at the feature-flag level means this cap only
     * matters when a caller explicitly opts into the capped reader — the
     * existing {@link #preRecall} snippet pipeline is independently bounded
     * by {@link #MAX_SNIPPETS}.
     */
    @Value("${acp.memory.l1l2.max-l1:30}")
    private int maxL1 = 30;

    public ActiveMemoryService(AgentMemoryService memoryService) {
        this.memoryService = memoryService;
    }

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

    /**
     * Apply the shadow-mode annotation to a memory-content string. Returns
     * the input unchanged when {@code shadowMode=false} or when {@code content}
     * is null. Idempotent: if {@code content} already starts with
     * {@link #SHADOW_ANNOTATION_PREFIX}, it is not double-prefixed.
     *
     * <p>Callers: {@link #snippet(Map)} (pre-recall path used by GroundingService),
     * {@code AgentRunService.loadMemorySection} (agent-run prompt assembly), and
     * {@code AgentPromptAssemblyService.loadMemoriesByCategory /
     * loadSharedMemories} (deterministic prompt assembly entry point).
     * Centralising here ensures every path reaches the LLM with the same
     * marker contract (plan §8 / PR-72 C2 / PR-82 R5-C1).
     *
     * <p><b>Known limitation (PR-82 R5-N5):</b> the idempotency guard is an
     * exact {@code startsWith(SHADOW_ANNOTATION_PREFIX)} check. If any upstream
     * middleware normalises the literal prefix (e.g. full-width → half-width
     * punctuation, NFC vs NFKC unicode folding) the guard can miss and the
     * marker gets re-applied. In practice all our call sites pass raw DB
     * content straight through, so this is a documented edge-case, not a
     * behavioural bug — we intentionally trade theoretical robustness for a
     * one-line O(len) check.
     */
    public static String applyShadowMarker(String content, boolean shadowMode) {
        if (!shadowMode || content == null) return content;
        if (content.startsWith(SHADOW_ANNOTATION_PREFIX)) return content;
        return SHADOW_ANNOTATION_PREFIX + content;
    }

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
     * Exceptions from READ operations are NOT swallowed here — {@code GroundingService}
     * wraps the whole call so a storage error surfaces there (single catch
     * point; "no silent fallback" red-line).
     *
     * <p><b>PR-82 R5-N3 — access-log write contract:</b> {@code preRecall} also
     * calls {@link #logAccess} for each returned snippet (powers the
     * {@code implicit_co_sign} Strategy B in MemoryPromotionExtractor). A
     * {@link DataAccessException} thrown by the access-log INSERT is caught
     * and logged at WARN only — an access-log miss at worst under-counts
     * co-signers on that memory for that day, which is cosmetic, whereas
     * breaking the user-facing chat path would be a production incident.
     * All other exceptions (NPE, {@link IllegalArgumentException}, etc.)
     * still propagate; the read contract is unchanged.
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
                    logAccess(row, userId);
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
                    logAccess(row, userId);
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
     * PR-72 C3: write one access-log row per (memory, user, day) whenever a
     * memory is materialised into a chat pre-recall payload. Powers the
     * {@code implicit_co_sign} strategy in {@link MemoryPromotionExtractor}
     * which counts distinct users over 90 days. No-op when userId is null
     * (system / cron caller — recordMemoryAccess itself guards this too).
     */
    private void logAccess(Map<String, Object> row, String userId) {
        if (userId == null || userId.isBlank()) return;
        Object pidObj = row.get("pid");
        if (pidObj == null) return;
        String pid = String.valueOf(pidObj);
        try {
            memoryService.recordMemoryAccess(pid, userId);
        } catch (DataAccessException e) {
            // allowed-catch (PR-82 R5-N3): access-log write failure is
            // cosmetic (at worst under-counts co-signers on this memory for
            // today); it must NOT break the user-facing chat read path.
            // Narrow catch — only DB-layer exceptions; NPE / validation
            // errors in the row itself still propagate.
            log.warn("access-log write failed for memory={} user={}: {}",
                    pid, userId, e.getMessage());
        }
    }

    /**
     * Phase 4 (PR-85) — capped L1 recall. Delegates to
     * {@link AgentMemoryService#loadL1Capped} with the configured
     * {@link #maxL1} ceiling. Callers that want raw L1 rows (e.g. a future
     * "L1 peek" admin endpoint or an alternative prompt assembler that
     * wants to inject working-memory verbatim) must go through this method
     * so the {@code acp.memory.l1l2.max-l1} knob has a single honouring
     * surface.
     *
     * <p>Returns an empty list when {@code scopeKey} is null/blank — system
     * / cron callers have no user partition to cap.
     */
    public List<Map<String, Object>> recallL1Capped(Long tenantId, String scopeKey) {
        if (tenantId == null) return List.of();
        if (scopeKey == null || scopeKey.isBlank()) return List.of();
        return memoryService.loadL1Capped(tenantId, scopeKey, maxL1);
    }

    /** Returns the cap that will be applied by {@link #recallL1Capped} — {@code acp.memory.l1l2.max-l1} (default 30). */
    public int getMaxL1() {
        return maxL1;
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
        contentStr = applyShadowMarker(contentStr, isShadow);
        m.put("content", contentStr);
        m.put("importance", row.get("importance"));
        m.put("scope", row.get("scope"));
        m.put("shadow_mode", isShadow);
        return m;
    }
}
