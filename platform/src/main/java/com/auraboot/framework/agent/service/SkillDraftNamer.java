package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * ACP Learning Loop §4.6 — LLM Namer.
 *
 * SkillDraftGenerator emits a deterministic code like
 * {@code auto.crm_lead_update.ab12cd34ef56} — traceable but unreadable.
 * Before the draft goes to human review, ask the configured LLM to
 * propose a better skill_code + description based on the pattern
 * signature and sample runs.
 *
 * Best-effort: if no LLM provider is configured or the call fails, leave
 * the deterministic name alone. The review UI stays functional either
 * way — it just shows an uglier name.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillDraftNamer {

    private static final String SYSTEM_PROMPT =
            "You are naming an auto-generated Skill for a business ACP agent runtime. " +
            "Given the pattern signature, produce a concise skill_code and one-sentence " +
            "description. The skill_code MUST: " +
            " - start with a domain prefix like crm. / pm. / hr. / analytics. " +
            " - use lowercase snake-case after the dot " +
            " - be ≤ 60 chars " +
            " - describe the action concretely (e.g. crm.lead.batch_status_update, " +
            "   not generic.update_thing) " +
            "Reply ONLY with compact JSON: " +
            "{\"skill_code\":\"...\",\"description\":\"...\"}";

    private final JdbcTemplate jdbcTemplate;
    private final LlmProviderFactory providerFactory;
    private final ObjectMapper objectMapper;

    /**
     * Produce a better name for one draft and persist it. Returns true on
     * successful rename, false on any failure (including no LLM configured)
     * — caller can ignore the boolean; the draft stays usable either way.
     */
    public boolean renameDraft(Long tenantId, String draftPid) {
        // N11 fix: load draft scoped to tenant. If the draft does not belong to
        // this tenant (or does not exist), bail out BEFORE calling the LLM.
        Map<String, Object> draft = loadDraft(tenantId, draftPid);
        if (draft == null) {
            log.debug("renameDraft: no draft found for tenant={} pid={}", tenantId, draftPid);
            return false;
        }
        // Build the signature payload for the LLM from the related pattern.
        String patternHash = (String) draft.get("source_pattern_hash");
        Map<String, Object> pattern = loadPatternBySignatureHash(patternHash);
        if (pattern == null) {
            log.debug("renameDraft: pattern not found for hash={}", patternHash);
            return false;
        }

        Proposal proposal = askLlm(tenantId, (String) draft.get("contract_yaml"),
                (String) pattern.get("signature_json"),
                ((Number) pattern.get("invocation_count")).longValue(),
                ((Number) pattern.get("success_rate")).doubleValue());
        if (proposal == null) return false;

        if (!isValidCode(proposal.code)) {
            log.debug("renameDraft: LLM returned invalid code '{}', skipping", proposal.code);
            return false;
        }

        int updated = jdbcTemplate.update(
                "UPDATE ab_agent_skill_draft " +
                        "SET draft_skill_code = ?, review_comment = ? " +
                        "WHERE pid = ? AND tenant_id = ? AND status = 'DRAFT_PENDING_REVIEW'",
                proposal.code, proposal.description, draftPid, tenantId);
        if (updated == 1) {
            log.info("SkillDraftNamer: draft {} renamed: auto.* → {}", draftPid, proposal.code);
            return true;
        }
        return false;
    }

    /**
     * Batch rename all DRAFT_PENDING_REVIEW drafts whose draft_skill_code
     * starts with the "auto." prefix. Runs after SkillDraftGenerator in
     * the nightly chain (or on-demand via admin API).
     */
    public int renameAllAutoDrafts(Long tenantId) {
        List<String> pids = jdbcTemplate.queryForList(
                "SELECT pid FROM ab_agent_skill_draft " +
                        "WHERE tenant_id = ? AND status = 'DRAFT_PENDING_REVIEW' " +
                        "  AND draft_skill_code LIKE 'auto.%' " +
                        "ORDER BY created_at DESC LIMIT 50",
                String.class, tenantId);
        int renamed = 0;
        for (String pid : pids) {
            if (renameDraft(tenantId, pid)) renamed++;
        }
        return renamed;
    }

    // =========================================================================

    private Proposal askLlm(Long tenantId, String yaml, String signatureJson,
                             long invocationCount, double successRate) {
        LlmProviderFactory.ProviderConfig config;
        try {
            config = providerFactory.resolveConfig(tenantId, null);
        } catch (Exception e) {
            log.debug("renameDraft: no LLM config available: {}", e.getMessage());
            return null;
        }
        if (config == null) return null;

        LlmProvider provider = providerFactory.getProvider(config.getProviderCode());
        if (provider == null) return null;

        String userMessage = "Pattern signature: " + signatureJson + "\n"
                + "Invocations: " + invocationCount + ", success_rate: " + successRate + "\n"
                + "Deterministic contract YAML:\n" + yaml;

        LlmChatRequest request = LlmChatRequest.builder()
                .model(config.getDefaultModel())
                .systemPrompt(SYSTEM_PROMPT)
                .messages(List.of(
                        LlmChatRequest.Message.builder().role("user").content(userMessage).build()))
                .maxTokens(200)
                .build();

        try {
            LlmChatResponse response = provider.chat(request, config.getApiKey(), config.getBaseUrl());
            String text = extractText(response);
            if (text == null || text.isBlank()) return null;

            String json = extractJson(text);
            Map<String, Object> parsed = objectMapper.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
            String code = (String) parsed.get("skill_code");
            String desc = (String) parsed.get("description");
            if (code == null || desc == null) return null;
            return new Proposal(code.trim(), desc.trim());
        } catch (Exception e) {
            log.debug("renameDraft LLM call failed: {}", e.getMessage());
            return null;
        }
    }

    private String extractText(LlmChatResponse response) {
        if (response == null || response.getContent() == null) return null;
        StringBuilder sb = new StringBuilder();
        for (LlmChatResponse.ContentBlock b : response.getContent()) {
            if ("text".equals(b.getType()) && b.getText() != null) sb.append(b.getText());
        }
        return sb.toString();
    }

    /** LLMs love wrapping JSON in ```json fences — strip and return the body. */
    private String extractJson(String text) {
        String t = text.trim();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            if (firstNewline > 0) t = t.substring(firstNewline + 1);
            int fenceEnd = t.lastIndexOf("```");
            if (fenceEnd >= 0) t = t.substring(0, fenceEnd);
        }
        return t.trim();
    }

    /**
     * Allowed: domain prefix + "." + snake_case segments, total ≤ 60 chars.
     * Tightened (N11): disallow consecutive dots ({@code a..b}) and trailing
     * dots ({@code a.b.}). Each segment after the first must start with a
     * lowercase letter.
     */
    public boolean isValidCode(String code) {
        if (code == null || code.isBlank() || code.length() > 60) return false;
        // Reject consecutive dots and trailing dot up front.
        if (code.contains("..") || code.endsWith(".")) return false;
        return code.matches("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9_]*)+$");
    }

    private Map<String, Object> loadDraft(Long tenantId, String draftPid) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT pid, tenant_id, draft_skill_code, contract_yaml, source_pattern_hash, status " +
                        "FROM ab_agent_skill_draft WHERE pid = ? AND tenant_id = ?",
                draftPid, tenantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> loadPatternBySignatureHash(String hash) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT pattern_signature::text AS signature_json, invocation_count, success_rate " +
                        "FROM ab_agent_learning_pattern WHERE pattern_hash = ? LIMIT 1", hash);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Small record tuple — avoid Lombok for a 2-field internal type. */
    private record Proposal(String code, String description) {}
}
