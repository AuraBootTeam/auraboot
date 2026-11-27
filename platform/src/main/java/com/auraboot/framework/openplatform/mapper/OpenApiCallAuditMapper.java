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
    @com.baomidou.mybatisplus.annotation.InterceptorIgnore(tenantLine = "true")
    @org.apache.ibatis.annotations.Insert("""
            INSERT INTO ab_open_api_call_audit
                (pid, tenant_id, application_pid, installation_pid, token_pid, request_id,
                 http_method, request_path, response_status, duration_ms, remote_address, occurred_at, business_details)
            VALUES (#{audit.pid}, #{audit.tenantId}, #{audit.applicationPid}, #{audit.installationPid},
                    #{audit.tokenPid}, #{audit.requestId}, #{audit.httpMethod}, #{audit.requestPath},
                    #{audit.responseStatus}, #{audit.durationMs}, #{audit.remoteAddress}, #{audit.occurredAt},
                    #{audit.businessDetails,jdbcType=OTHER,typeHandler=com.auraboot.framework.application.typehandler.JsonNodeTypeHandler})
            """)
    int insertAuthenticationFailure(@Param("audit") OpenApiCallAudit audit);

    @org.apache.ibatis.annotations.Results({@org.apache.ibatis.annotations.Result(column = "business_details", property = "businessDetails",
            typeHandler = com.auraboot.framework.application.typehandler.JsonNodeTypeHandler.class)})
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

    @com.baomidou.mybatisplus.annotation.InterceptorIgnore(tenantLine = "true")
    @org.apache.ibatis.annotations.Update("""
            UPDATE ab_open_api_call_audit SET http_details = #{details,jdbcType=OTHER,typeHandler=com.auraboot.framework.application.typehandler.JsonNodeTypeHandler}
            WHERE pid = #{pid} AND tenant_id = #{tenantId} AND installation_pid = #{installationPid}
            """)
    int updateHttpDetails(@Param("pid") String pid, @Param("tenantId") Long tenantId,
                          @Param("installationPid") String installationPid,
                          @Param("details") com.fasterxml.jackson.databind.JsonNode details);

    @org.apache.ibatis.annotations.ResultMap("mybatis-plus_OpenApiCallAudit")
    @Select("""
            SELECT * FROM ab_open_api_call_audit
            WHERE tenant_id = #{tenantId} AND installation_pid = #{installationPid} AND pid = #{pid}
            """)
    OpenApiCallAudit findDetail(@Param("tenantId") Long tenantId,
            @Param("installationPid") String installationPid, @Param("pid") String pid);

    @org.apache.ibatis.annotations.Results({@org.apache.ibatis.annotations.Result(column = "business_details", property = "businessDetails",
            typeHandler = com.auraboot.framework.application.typehandler.JsonNodeTypeHandler.class)})
    @Select("""
            SELECT id,pid,tenant_id,application_pid,installation_pid,request_id,http_method,request_path,
                   response_status,duration_ms,occurred_at,business_details
            FROM ab_open_api_call_audit
            WHERE tenant_id = #{tenantId} AND installation_pid = #{installationPid}
              AND occurred_at >= #{since}
              AND (CAST(#{requestId} AS varchar) IS NULL OR request_id = #{requestId})
              AND (CAST(#{status} AS integer) IS NULL OR response_status = #{status})
              AND (CAST(#{keyword} AS varchar) IS NULL OR request_id ILIKE #{keyword} ESCAPE '\\'
                   OR request_path ILIKE #{keyword} ESCAPE '\\')
              AND (CAST(#{method} AS varchar) IS NULL OR http_method = #{method})
              AND (#{statusClass} = 'all' OR (#{statusClass} = 'error' AND response_status >= 400)
                   OR (#{statusClass} = 'success' AND response_status BETWEEN 200 AND 299))
            ORDER BY occurred_at DESC, id DESC LIMIT #{limit} OFFSET #{offset}
            """)
    List<OpenApiCallAudit> searchForOperations(@Param("tenantId") Long tenantId,
            @Param("installationPid") String installationPid, @Param("requestId") String requestId,
            @Param("status") Integer status, @Param("keyword") String keyword,
            @Param("method") String method, @Param("statusClass") String statusClass,
            @Param("since") Instant since, @Param("limit") int limit, @Param("offset") int offset);

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
