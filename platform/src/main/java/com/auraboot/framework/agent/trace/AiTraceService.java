package com.auraboot.framework.agent.trace;

import com.auraboot.framework.agent.trace.entity.AiTrace;
import com.auraboot.framework.agent.trace.entity.AiTraceSpan;
import com.auraboot.framework.agent.trace.mapper.AiTraceMapper;
import com.auraboot.framework.agent.trace.mapper.AiTraceSpanMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.auraboot.framework.agent.trace.dto.TraceStatsResponse;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.auraboot.framework.common.constant.StatusConstants;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiTraceService {

    private final AiTraceMapper traceMapper;
    private final AiTraceSpanMapper spanMapper;
    private final ObjectMapper objectMapper;

    // =========================================================================
    // Trace lifecycle
    // =========================================================================

    public TraceContext createTrace(Long tenantId, String sessionId, String userMessage,
                                    Long userId, Map<String, Object> metadata) {
        try {
            Instant now = Instant.now();
            String traceId = UUID.randomUUID().toString();

            traceMapper.insertTraceRecord(
                    traceId,
                    tenantId,
                    sessionId,
                    "chat",
                    userId,
                    userMessage,
                    StatusConstants.IN_PROGRESS,
                    toJson(metadata),
                    now
            );

            return TraceContext.builder()
                    .traceId(traceId)
                    .tenantId(tenantId)
                    .sessionId(sessionId)
                    .startTime(now)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to create trace: {}", e.getMessage());
            return null;
        }
    }

    public void endTrace(TraceContext ctx, String output, String status) {
        if (ctx == null) return;
        try {
            Instant now = Instant.now();
            long duration = Duration.between(ctx.getStartTime(), now).toMillis();

            traceMapper.finishTraceSuccess(ctx.getTraceId(), output, status, now, duration);
        } catch (Exception e) {
            log.warn("Failed to end trace {}: {}", ctx.getTraceId(), e.getMessage());
        }
    }

    public void endTraceWithError(TraceContext ctx, String errorMessage) {
        if (ctx == null) return;
        try {
            Instant now = Instant.now();
            long duration = Duration.between(ctx.getStartTime(), now).toMillis();

            traceMapper.finishTraceError(ctx.getTraceId(), errorMessage, now, duration);
        } catch (Exception e) {
            log.warn("Failed to end trace with error {}: {}", ctx.getTraceId(), e.getMessage());
        }
    }

    // =========================================================================
    // Span lifecycle
    // =========================================================================

    public SpanContext startSpan(TraceContext ctx, String parentSpanId,
                                 String type, String name, Object input) {
        if (ctx == null) return null;
        try {
            Instant now = Instant.now();
            String spanId = UUID.randomUUID().toString();
            int seq = ctx.nextSequence();

            spanMapper.insertSpanRecord(
                    spanId,
                    ctx.getTraceId(),
                    parentSpanId,
                    ctx.getTenantId(),
                    type,
                    name,
                    toJson(input),
                    StatusConstants.IN_PROGRESS,
                    "default",
                    now,
                    seq
            );

            return SpanContext.builder()
                    .spanId(spanId)
                    .traceId(ctx.getTraceId())
                    .parentSpanId(parentSpanId)
                    .type(type)
                    .name(name)
                    .startTime(now)
                    .sequenceOrder(seq)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to start span: {}", e.getMessage());
            return null;
        }
    }

    public void endSpan(SpanContext ctx, Object output, String status) {
        if (ctx == null) return;
        try {
            Instant now = Instant.now();
            long duration = Duration.between(ctx.getStartTime(), now).toMillis();
            spanMapper.finishSpan(ctx.getSpanId(), toJson(output), status, now, duration);
        } catch (Exception e) {
            log.warn("Failed to end span {}: {}", ctx.getSpanId(), e.getMessage());
        }
    }

    public void recordGeneration(SpanContext ctx, String model, Integer inputTokens,
                                  Integer outputTokens, BigDecimal cost,
                                  String stopReason, Object toolDefinitions,
                                  Object toolCalls) {
        if (ctx == null) return;
        try {
            spanMapper.updateGeneration(
                    ctx.getSpanId(),
                    model,
                    inputTokens,
                    outputTokens,
                    cost,
                    stopReason,
                    toJson(toolDefinitions),
                    toJson(toolCalls)
            );

            // Update trace aggregates
            if (inputTokens != null || outputTokens != null) {
                traceMapper.update(null,
                        new LambdaUpdateWrapper<AiTrace>()
                                .eq(AiTrace::getTraceId, ctx.getTraceId())
                                .setSql(inputTokens != null, "total_input_tokens = total_input_tokens + " + inputTokens)
                                .setSql(outputTokens != null, "total_output_tokens = total_output_tokens + " + outputTokens)
                                .setSql(cost != null, "total_cost = total_cost + " + cost));
            }
        } catch (Exception e) {
            log.warn("Failed to record generation {}: {}", ctx.getSpanId(), e.getMessage());
        }
    }

    public void updateSpanStatus(String spanId, String status) {
        if (spanId == null) return;
        try {
            spanMapper.updateSpanStatusExplicit(spanId, status);
        } catch (Exception e) {
            log.warn("Failed to update span status {}: {}", spanId, e.getMessage());
        }
    }

    public TraceContext findActiveTrace(String sessionId) {
        try {
            AiTrace trace = traceMapper.selectOne(
                    new LambdaQueryWrapper<AiTrace>()
                            .eq(AiTrace::getSessionId, sessionId)
                            .eq(AiTrace::getStatus, "in_progress")
                            .orderByDesc(AiTrace::getStartTime)
                            .last("LIMIT 1"));
            if (trace == null) return null;

            return TraceContext.builder()
                    .traceId(trace.getTraceId())
                    .tenantId(trace.getTenantId())
                    .sessionId(trace.getSessionId())
                    .startTime(trace.getStartTime())
                    .build();
        } catch (Exception e) {
            log.warn("Failed to find active trace for session {}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // Query methods (for API)
    // =========================================================================

    public IPage<AiTrace> listTraces(Long tenantId, int pageNum, int pageSize,
                                      String status, String sessionId, String keyword,
                                      Instant startTime, Instant endTime) {
        LambdaQueryWrapper<AiTrace> qw = new LambdaQueryWrapper<AiTrace>()
                .eq(AiTrace::getTenantId, tenantId)
                .eq(status != null, AiTrace::getStatus, status)
                .eq(sessionId != null, AiTrace::getSessionId, sessionId)
                .ge(startTime != null, AiTrace::getStartTime, startTime)
                .le(endTime != null, AiTrace::getStartTime, endTime)
                .and(keyword != null && !keyword.isBlank(), w -> w
                        .like(AiTrace::getInput, keyword)
                        .or().like(AiTrace::getOutput, keyword))
                .orderByDesc(AiTrace::getStartTime);

        // Use custom selectPage with @ResultMap to ensure JSONB/array typeHandlers are applied
        return traceMapper.selectPageWithResultMap(new Page<>(pageNum, pageSize), qw);
    }

    public AiTrace getTrace(String traceId) {
        return traceMapper.selectByTraceId(traceId);
    }

    public List<AiTraceSpan> getSpans(String traceId) {
        return spanMapper.selectList(
                new LambdaQueryWrapper<AiTraceSpan>()
                        .eq(AiTraceSpan::getTraceId, traceId)
                        .orderByAsc(AiTraceSpan::getSequenceOrder));
    }

    public TraceStatsResponse getStats(Long tenantId) {
        Map<String, Object> row = traceMapper.selectStats(tenantId);
        TraceStatsResponse stats = new TraceStatsResponse();
        stats.setTotalTraces(toLong(row.get("total_traces")));
        stats.setSuccessCount(toLong(row.get("success_count")));
        stats.setErrorCount(toLong(row.get("error_count")));
        stats.setTotalCost(row.get("total_cost") != null
                ? new BigDecimal(row.get("total_cost").toString()) : BigDecimal.ZERO);
        stats.setTotalInputTokens(toLong(row.get("total_input_tokens")));
        stats.setTotalOutputTokens(toLong(row.get("total_output_tokens")));
        stats.setAvgDurationMs(row.get("avg_duration_ms") != null
                ? ((Number) row.get("avg_duration_ms")).doubleValue() : null);
        long total = stats.getTotalTraces();
        stats.setSuccessRate(total > 0
                ? Math.round(stats.getSuccessCount() * 10000.0 / total) / 100.0 : 0);
        return stats;
    }

    private static long toLong(Object val) {
        return val != null ? ((Number) val).longValue() : 0L;
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Failed to serialize trace payload: {}", e.getMessage());
            return null;
        }
    }
}
