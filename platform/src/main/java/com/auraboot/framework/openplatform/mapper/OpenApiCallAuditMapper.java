package com.auraboot.framework.openplatform.mapper;

import com.auraboot.framework.openplatform.entity.OpenApiCallAudit;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

@Mapper
public interface OpenApiCallAuditMapper extends BaseMapper<OpenApiCallAudit> {
    @Select("""
            SELECT * FROM ab_open_api_call_audit
            WHERE tenant_id = #{tenantId} AND installation_pid = #{installationPid}
              AND (CAST(#{requestId} AS varchar) IS NULL OR request_id = #{requestId})
              AND (CAST(#{status} AS integer) IS NULL OR response_status = #{status})
            ORDER BY occurred_at DESC, id DESC LIMIT #{limit}
            """)
    List<OpenApiCallAudit> findForOperations(@Param("tenantId") Long tenantId,
                                              @Param("installationPid") String installationPid,
                                              @Param("requestId") String requestId,
                                              @Param("status") Integer status,
                                              @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*) AS total_calls,
                   COUNT(*) FILTER (WHERE response_status >= 400) AS error_calls,
                   COUNT(*) FILTER (WHERE response_status = 429) AS throttled_calls,
                   COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms), 0) AS p95_duration_ms
            FROM ab_open_api_call_audit
            WHERE tenant_id = #{tenantId} AND installation_pid = #{installationPid}
              AND occurred_at >= #{since}
            """)
    OperationsSummary summarize(@Param("tenantId") Long tenantId,
                                @Param("installationPid") String installationPid,
                                @Param("since") Instant since);

    record OperationsSummary(long totalCalls, long errorCalls, long throttledCalls,
                             double p95DurationMs) { }
}
