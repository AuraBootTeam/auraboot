package com.auraboot.framework.agent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Dry-Run Support Registry (learning-loop.md §6.0.2).
 *
 * Per ACP spec, Shadow Mode needs to know whether a given tool_ref can be
 * replayed without side effects. Three support levels are recognised:
 *   FULL       — invocable as-is (read-only named query, MCP tool with
 *                annotations.readOnly=true)
 *   SIMULATED  — supports validation + before-snapshot but skips commit
 *                (dsl_command with CommandPipeline dry-run hooked up)
 *   NONE       — unshadowable; promotion bypasses Shadow Mode straight to
 *                reinforced human gate
 *
 * Match precedence:
 *   1. tenant-specific exact match
 *   2. tenant-specific prefix match (trailing '*')
 *   3. platform default (tenant_id = -1) exact match
 *   4. platform default prefix match
 *   5. no match → NONE (fail-secure)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DryRunSupportRegistry {

    public enum SupportLevel { FULL, SIMULATED, NONE }

    private static final Long PLATFORM_TENANT = -1L;

    private final JdbcTemplate jdbcTemplate;

    public SupportLevel lookup(Long tenantId, String toolRef) {
        if (toolRef == null || toolRef.isBlank()) return SupportLevel.NONE;

        SupportLevel tenantLevel = lookupForTenant(tenantId, toolRef);
        if (tenantLevel != null) return tenantLevel;

        SupportLevel platformLevel = lookupForTenant(PLATFORM_TENANT, toolRef);
        return platformLevel != null ? platformLevel : SupportLevel.NONE;
    }

    private SupportLevel lookupForTenant(Long tenantId, String toolRef) {
        if (tenantId == null) return null;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT tool_ref_pattern, support_level FROM ab_agent_dry_run_support " +
                        "WHERE tenant_id = ?", tenantId);
        String best = null;
        String bestLevel = null;
        for (Map<String, Object> r : rows) {
            String pattern = (String) r.get("tool_ref_pattern");
            if (pattern == null) continue;
            if (pattern.equals(toolRef)) {
                return SupportLevel.valueOf((String) r.get("support_level"));
            }
            if (pattern.endsWith("*")) {
                String prefix = pattern.substring(0, pattern.length() - 1);
                if (toolRef.startsWith(prefix)) {
                    if (best == null || prefix.length() > best.length()) {
                        best = prefix;
                        bestLevel = (String) r.get("support_level");
                    }
                }
            }
        }
        return bestLevel == null ? null : SupportLevel.valueOf(bestLevel);
    }
}
