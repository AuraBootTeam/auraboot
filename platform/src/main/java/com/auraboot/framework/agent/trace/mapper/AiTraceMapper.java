package com.auraboot.framework.agent.trace.mapper;

import com.auraboot.framework.agent.trace.entity.AiTrace;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

@Mapper
public interface AiTraceMapper extends BaseMapper<AiTrace> {

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
