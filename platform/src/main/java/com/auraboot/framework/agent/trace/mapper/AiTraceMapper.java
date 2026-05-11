package com.auraboot.framework.agent.trace.mapper;

import com.auraboot.framework.agent.trace.entity.AiTrace;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

@Mapper
public interface AiTraceMapper extends BaseMapper<AiTrace> {

    @Insert("""
        INSERT INTO ab_ai_trace (
            trace_id, tenant_id, session_id, name, user_id, input, status, metadata, start_time
        ) VALUES (
            #{traceId}, #{tenantId}, #{sessionId}, #{name}, #{userId}, #{input}, #{status},
            CAST(#{metadataJson} AS jsonb), #{startTime}
        )
    """)
    @InterceptorIgnore(tenantLine = "true")
    int insertTraceRecord(
            @Param("traceId") String traceId,
            @Param("tenantId") Long tenantId,
            @Param("sessionId") String sessionId,
            @Param("name") String name,
            @Param("userId") Long userId,
            @Param("input") String input,
            @Param("status") String status,
            @Param("metadataJson") String metadataJson,
            @Param("startTime") java.time.Instant startTime);

    @Update("""
        UPDATE ab_ai_trace
        SET output = #{output},
            status = #{status},
            end_time = #{endTime},
            duration_ms = #{durationMs}
        WHERE trace_id = #{traceId}
    """)
    @InterceptorIgnore(tenantLine = "true")
    int finishTraceSuccess(
            @Param("traceId") String traceId,
            @Param("output") String output,
            @Param("status") String status,
            @Param("endTime") java.time.Instant endTime,
            @Param("durationMs") long durationMs);

    @Update("""
        UPDATE ab_ai_trace
        SET status = 'error',
            error_message = #{errorMessage},
            end_time = #{endTime},
            duration_ms = #{durationMs}
        WHERE trace_id = #{traceId}
    """)
    @InterceptorIgnore(tenantLine = "true")
    int finishTraceError(
            @Param("traceId") String traceId,
            @Param("errorMessage") String errorMessage,
            @Param("endTime") java.time.Instant endTime,
            @Param("durationMs") long durationMs);

    /**
     * selectPage with autoResultMap to handle JSONB/array type handlers.
     * BaseMapper.selectPage uses default resultMap which skips typeHandlers.
     */
    @ResultMap("mybatis-plus_AiTrace")
    @Select("SELECT * FROM ab_ai_trace ${ew.customSqlSegment}")
    List<AiTrace> selectListWithResultMap(@Param(Constants.WRAPPER) Wrapper<AiTrace> wrapper);

    @ResultMap("mybatis-plus_AiTrace")
    @Select("SELECT * FROM ab_ai_trace ${ew.customSqlSegment}")
    IPage<AiTrace> selectPageWithResultMap(IPage<AiTrace> page, @Param(Constants.WRAPPER) Wrapper<AiTrace> wrapper);

    @ResultMap("mybatis-plus_AiTrace")
    @Select("SELECT * FROM ab_ai_trace WHERE trace_id = #{traceId} LIMIT 1")
    AiTrace selectByTraceId(@Param("traceId") String traceId);

    @ResultMap("mybatis-plus_AiTrace")
    @Select("""
        SELECT * FROM ab_ai_trace
        WHERE tenant_id = #{tenantId}
          AND trace_id = #{traceId}
        LIMIT 1
    """)
    AiTrace selectByTenantAndTraceId(
            @Param("tenantId") Long tenantId,
            @Param("traceId") String traceId);

    @Select("""
        SELECT
            COUNT(*)                                                     AS total_traces,
            COUNT(*) FILTER (WHERE status = 'success')                   AS success_count,
            COUNT(*) FILTER (WHERE status = 'error')                     AS error_count,
            ROUND(AVG(duration_ms) FILTER (WHERE duration_ms IS NOT NULL)) AS avg_duration_ms,
            COALESCE(SUM(total_cost), 0)                                 AS total_cost,
            COALESCE(SUM(total_input_tokens), 0)                         AS total_input_tokens,
            COALESCE(SUM(total_output_tokens), 0)                        AS total_output_tokens
        FROM ab_ai_trace
        WHERE tenant_id = #{tenantId}
    """)
    Map<String, Object> selectStats(@Param("tenantId") Long tenantId);
}
