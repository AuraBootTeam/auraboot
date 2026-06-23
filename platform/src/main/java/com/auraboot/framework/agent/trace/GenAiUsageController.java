package com.auraboot.framework.agent.trace;

import com.auraboot.framework.agent.trace.dto.GenAiUsageSummary;
import com.auraboot.framework.agent.trace.mapper.GenAiUsageMapper;
import com.auraboot.framework.application.tenant.MetaContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read API over the durable LLM usage/cost ledger (A-G6 analysis layer; SoT §2.5).
 * Tenant-scoped from {@link MetaContext} like {@code AiTraceController} — auth-gated,
 * no cross-tenant access.
 */
@RestController
@RequestMapping("/api/ai/usage")
@RequiredArgsConstructor
public class GenAiUsageController {

    private final GenAiUsageMapper genAiUsageMapper;

    /** Per-model token/cost rollup for the current tenant. */
    @GetMapping("/summary")
    public List<GenAiUsageSummary> summary() {
        Long tenantId = MetaContext.getCurrentTenantId();
        return genAiUsageMapper.summaryByModel(tenantId);
    }
}
