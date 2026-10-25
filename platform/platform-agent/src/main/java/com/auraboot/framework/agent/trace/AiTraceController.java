package com.auraboot.framework.agent.trace;

import com.auraboot.framework.agent.trace.dto.TraceDetailResponse;
import com.auraboot.framework.agent.trace.dto.TraceStatsResponse;
import com.auraboot.framework.agent.trace.entity.AiTrace;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

/**
 * Read API over the LLM call-trace store. These endpoints expose raw prompts,
 * keywords, tool-calls and per-tenant cost/usage across ALL users in the tenant
 * (filtered by tenantId, not by the requesting user), so they are the LLM
 * observability layer of agent runs. Gated as full-tenant agent-run observability
 * — the same guard the sibling agent-run consoles ({@code AgentRunController},
 * {@code AgentRunOpsController}, {@code AgentRunAuditController}) already use — so a
 * plain authenticated member cannot read other users' AI activity. Without this the
 * handlers are un-annotated and the interceptor shadow-allows any logged-in user.
 */
@RestController
@RequestMapping("/api/ai/traces")
@RequiredArgsConstructor
@RequirePermission(MetaPermission.ACP_AGENT_RUN_ADMIN)
public class AiTraceController {

    private final AiTraceService aiTraceService;

    @GetMapping
    public IPage<AiTrace> listTraces(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Instant startTime,
            @RequestParam(required = false) Instant endTime) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return aiTraceService.listTraces(tenantId, pageNum, pageSize,
                status, sessionId, keyword, startTime, endTime);
    }

    @GetMapping("/stats")
    public TraceStatsResponse getStats() {
        Long tenantId = MetaContext.getCurrentTenantId();
        return aiTraceService.getStats(tenantId);
    }

    @GetMapping("/{traceId}")
    public TraceDetailResponse getTrace(@PathVariable String traceId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        AiTrace trace = aiTraceService.getTrace(tenantId, traceId);
        if (trace == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "trace_not_found");
        }

        TraceDetailResponse resp = new TraceDetailResponse();
        resp.setTrace(trace);
        resp.setSpans(aiTraceService.getSpans(tenantId, traceId));
        return resp;
    }
}
