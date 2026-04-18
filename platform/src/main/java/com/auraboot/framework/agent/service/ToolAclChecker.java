package com.auraboot.framework.agent.service;

import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * ACP Tool ACL 5-dimensional authorisation (ACP-Ideal §5.5).
 *
 * For every tool invocation, evaluate the rule-set in
 * {@code ab_agent_tool_acl} against the 5 dimensions:
 *   (tenant_id, profile_id, channel, run_kind, tool_ref)
 *
 * Evaluation order:
 *   1. Fetch ACTIVE rules for the tenant ordered by priority DESC, id ASC.
 *   2. First matching rule wins (priority takes precedence).
 *   3. Same-priority deny beats allow (explicit deny preferred when both
 *      end up at the same priority; enforced by ORDER BY effect ASC
 *      secondary).
 *   4. No match → DENY (fail-secure) with reason 'no_matching_rule'.
 *
 * Dimension matching:
 *   - NULL on a rule dimension = match-any for that axis
 *   - tool_ref_pattern supports EXACT or a single trailing '*' wildcard
 *     (e.g. 'cmd_*', 'nq_crm_account_list', 'cmd_update_lead')
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolAclChecker {

    @Data
    @Builder
    public static class Decision {
        /** true = allow; false = deny */
        private boolean allowed;
        /** PID of the rule that matched (null when no-match fail-secure). */
        private String matchedRulePid;
        private int matchedPriority;
        private String reason;
    }

    private final JdbcTemplate jdbcTemplate;

    /**
     * Check whether the 5-tuple is allowed. All dimensions are required
     * non-null for the request itself; rule rows may carry NULL to mean "any".
     */
    public Decision check(Long tenantId, String profileId, String channel,
                           String runKind, String toolRef) {
        if (tenantId == null || toolRef == null || toolRef.isBlank()) {
            return Decision.builder()
                    .allowed(false).matchedPriority(-1)
                    .reason("missing required dimension (tenantId / toolRef)")
                    .build();
        }

        // Pull all active rules for this tenant; in-memory evaluation is
        // cheap and avoids the complexity of encoding the priority-+-effect
        // tiebreak inside a single SQL. Caller volumes are bounded (tens of
        // rules per tenant in realistic deployments).
        List<Map<String, Object>> rules = jdbcTemplate.queryForList(
                "SELECT pid, profile_id, channel, run_kind, tool_ref_pattern, " +
                        "       effect, priority, reason " +
                        "FROM ab_agent_tool_acl " +
                        "WHERE tenant_id = ? AND active = TRUE " +
                        "ORDER BY priority DESC, " +
                        "         CASE effect WHEN 'deny' THEN 0 ELSE 1 END, " +
                        "         id ASC",
                tenantId);

        // Progressive fail-secure: a tenant with NO ACL rules at all gets
        // the pre-ACL behaviour (permit; other gates like Approval still
        // run). As soon as the admin adds the first rule, fail-secure
        // semantics kick in — no-match → deny. This avoids shipping an
        // immediate "everything denied" rollout; opt-in to lock down.
        if (rules.isEmpty()) {
            return Decision.builder()
                    .allowed(true).matchedPriority(-1)
                    .reason("no_acl_configured_for_tenant")
                    .build();
        }

        for (Map<String, Object> rule : rules) {
            if (!dimMatches((String) rule.get("profile_id"), profileId)) continue;
            if (!dimMatches((String) rule.get("channel"), channel)) continue;
            if (!dimMatches((String) rule.get("run_kind"), runKind)) continue;
            if (!toolMatches((String) rule.get("tool_ref_pattern"), toolRef)) continue;

            String effect = (String) rule.get("effect");
            int priority = ((Number) rule.get("priority")).intValue();
            Decision d = Decision.builder()
                    .allowed("allow".equals(effect))
                    .matchedRulePid((String) rule.get("pid"))
                    .matchedPriority(priority)
                    .reason((String) rule.get("reason"))
                    .build();
            log.debug("Tool ACL: tenant={} tool={} → {} (rule {} @priority {})",
                    tenantId, toolRef, effect, d.getMatchedRulePid(), priority);
            return d;
        }

        // Fail-secure default
        return Decision.builder()
                .allowed(false).matchedPriority(-1)
                .reason("no_matching_rule").build();
    }

    /** NULL on rule side = match any; non-null must equal request side. */
    private boolean dimMatches(String ruleValue, String requestValue) {
        if (ruleValue == null) return true;
        return ruleValue.equals(requestValue);
    }

    /**
     * Pattern matching with exact or single trailing-* wildcard:
     *   "cmd_*"   → cmd_create_lead ✓ / nq_x ✗
     *   "cmd_X"   → cmd_X ✓ / cmd_X_extra ✗
     */
    private boolean toolMatches(String pattern, String toolRef) {
        if (pattern == null || pattern.isBlank()) return false;
        if (pattern.endsWith("*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return toolRef.startsWith(prefix);
        }
        return pattern.equals(toolRef);
    }
}
