package com.auraboot.framework.agent.trace.mapper;

import com.auraboot.framework.agent.trace.entity.AiTraceSpan;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Mapper
public interface AiTraceSpanMapper extends BaseMapper<AiTraceSpan> {

    /**
     * Select spans by traceId using @ResultMap to ensure JSONB typeHandlers are applied.
     * LambdaQueryWrapper + selectList does NOT apply autoResultMap typeHandlers.
     */
    @Select("""
        SELECT * FROM ab_ai_trace_span
        WHERE trace_id = #{traceId}
        ORDER BY sequence_order ASC
    """)
    @InterceptorIgnore(tenantLine = "true")
    @ResultMap("mybatis-plus_AiTraceSpan")
    List<AiTraceSpan> selectByTraceId(@Param("traceId") String traceId);

    @Insert("""
        INSERT INTO ab_ai_trace_span (
            span_id, trace_id, parent_span_id, tenant_id, type, name, input, status, level, start_time, sequence_order
        ) VALUES (
            #{spanId}, #{traceId}, #{parentSpanId}, #{tenantId}, #{type}, #{name},
            CAST(#{inputJson} AS jsonb), #{status}, #{level}, #{startTime}, #{sequenceOrder}
        )
    """)
    @InterceptorIgnore(tenantLine = "true")
    int insertSpanRecord(
            @Param("spanId") String spanId,
            @Param("traceId") String traceId,
            @Param("parentSpanId") String parentSpanId,
            @Param("tenantId") Long tenantId,
            @Param("type") String type,
            @Param("name") String name,
            @Param("inputJson") String inputJson,
            @Param("status") String status,
            @Param("level") String level,
            @Param("startTime") Instant startTime,
            @Param("sequenceOrder") int sequenceOrder);

    @Update("""
        UPDATE ab_ai_trace_span
        SET output = CAST(#{outputJson} AS jsonb),
            status = #{status},
            end_time = #{endTime},
            duration_ms = #{durationMs}
        WHERE span_id = #{spanId}
    """)
    @InterceptorIgnore(tenantLine = "true")
    int finishSpan(
            @Param("spanId") String spanId,
            @Param("outputJson") String outputJson,
            @Param("status") String status,
            @Param("endTime") Instant endTime,
            @Param("durationMs") long durationMs);

    @Update("""
        UPDATE ab_ai_trace_span
        SET model = #{model},
            input_tokens = #{inputTokens},
            output_tokens = #{outputTokens},
            cost = #{cost},
            stop_reason = #{stopReason},
            tool_definitions = CAST(#{toolDefinitionsJson} AS jsonb),
            tool_calls = CAST(#{toolCallsJson} AS jsonb)
        WHERE span_id = #{spanId}
    """)
    @InterceptorIgnore(tenantLine = "true")
    int updateGeneration(
            @Param("spanId") String spanId,
            @Param("model") String model,
            @Param("inputTokens") Integer inputTokens,
            @Param("outputTokens") Integer outputTokens,
            @Param("cost") BigDecimal cost,
            @Param("stopReason") String stopReason,
            @Param("toolDefinitionsJson") String toolDefinitionsJson,
            @Param("toolCallsJson") String toolCallsJson);

    @Update("""
        UPDATE ab_ai_trace_span
        SET status = #{status}
        WHERE span_id = #{spanId}
    """)
    @InterceptorIgnore(tenantLine = "true")
    int updateSpanStatusExplicit(@Param("spanId") String spanId, @Param("status") String status);
}
