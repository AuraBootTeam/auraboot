package com.auraboot.framework.agent.trace;

import com.auraboot.framework.agent.trace.dto.TraceDetailResponse;
import com.auraboot.framework.agent.trace.dto.TraceStatsResponse;
import com.auraboot.framework.agent.trace.entity.AiTrace;
import com.auraboot.framework.application.tenant.MetaContext;
import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.RequiredArgsConstructor;
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
        TraceDetailResponse resp = new TraceDetailResponse();
        resp.setTrace(aiTraceService.getTrace(traceId));
        resp.setSpans(aiTraceService.getSpans(traceId));
        return resp;
    }
}
