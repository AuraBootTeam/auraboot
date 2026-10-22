package com.auraboot.framework.agent.trace;

import com.auraboot.framework.agent.trace.dto.GenAiUsageSummary;
import com.auraboot.framework.agent.trace.mapper.GenAiUsageMapper;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read API over the durable LLM usage/cost ledger (A-G6 analysis layer; SoT §2.5).
 * The summary rolls up token/cost across the WHOLE tenant (filtered by tenantId, not
 * by the requesting user), so it is full-tenant agent-run cost observability and is
 * gated with the same guard as the trace/agent-run consoles. Without an explicit
 * guard the handler is un-annotated and the interceptor shadow-allows any logged-in
 * user to read the tenant's aggregate AI spend.
 */
@RestController
@RequestMapping("/api/ai/usage")
@RequiredArgsConstructor
@RequirePermission(MetaPermission.ACP_AGENT_RUN_ADMIN)
public class GenAiUsageController {

    private final GenAiUsageMapper genAiUsageMapper;

    /** Per-model token/cost rollup for the current tenant. */
    @GetMapping("/summary")
    public List<GenAiUsageSummary> summary() {
        Long tenantId = MetaContext.getCurrentTenantId();
        return genAiUsageMapper.summaryByModel(tenantId);
    }
}
