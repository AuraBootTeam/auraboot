package com.auraboot.framework.agent.trace;

import com.auraboot.framework.agent.trace.dto.TraceDetailResponse;
import com.auraboot.framework.agent.trace.dto.TraceStatsResponse;
import com.auraboot.framework.agent.trace.entity.AiTrace;
import com.auraboot.framework.application.tenant.MetaContext;
import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/api/ai/traces")
@RequiredArgsConstructor
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
