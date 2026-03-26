package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.InvariantEvaluationLog;
import org.apache.ibatis.annotations.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Invariant Evaluation Log Mapper.
 *
 * @author AuraBoot Team
 * @since 2.5.0
 */
@Mapper
public interface InvariantEvaluationLogMapper {

    @Insert("""
        INSERT INTO ab_invariant_evaluation_log
        (tenant_id, invariant_code, invariant_type, scope_type, scope_ref,
         model_code, record_id, command_code, evaluation_result, severity,
         expression, error_message, context_snapshot, execution_time_ms, created_at)
        VALUES
        (#{tenantId}, #{invariantCode}, #{invariantType}, #{scopeType}, #{scopeRef},
         #{modelCode}, #{recordId}, #{commandCode}, #{evaluationResult}, #{severity},
         #{expression}, #{errorMessage}, #{contextSnapshot}::jsonb, #{executionTimeMs}, #{createdAt})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertLog(InvariantEvaluationLog log);

    @Select("""
        SELECT invariant_code, COUNT(*) as violation_count,
               MAX(created_at) as last_violation
        FROM ab_invariant_evaluation_log
        WHERE tenant_id = #{tenantId} AND evaluation_result = FALSE
        AND created_at >= #{since}
        GROUP BY invariant_code
        ORDER BY violation_count DESC
        """)
    List<Map<String, Object>> getViolationStats(@Param("tenantId") Long tenantId,
                                                 @Param("since") Instant since);

    @Select("""
        SELECT date_trunc('hour', created_at) as time_bucket,
               COUNT(*) as violation_count
        FROM ab_invariant_evaluation_log
        WHERE tenant_id = #{tenantId} AND invariant_code = #{invariantCode}
        AND evaluation_result = FALSE AND created_at >= #{since}
        GROUP BY time_bucket
        ORDER BY time_bucket
        """)
    List<Map<String, Object>> getViolationTrend(@Param("tenantId") Long tenantId,
                                                 @Param("invariantCode") String invariantCode,
                                                 @Param("since") Instant since);

    @Select("""
        SELECT * FROM ab_invariant_evaluation_log
        WHERE tenant_id = #{tenantId} AND evaluation_result = FALSE
        ORDER BY created_at DESC
        LIMIT #{limit}
        """)
    List<InvariantEvaluationLog> findRecentViolations(@Param("tenantId") Long tenantId,
                                                       @Param("limit") int limit);
}
