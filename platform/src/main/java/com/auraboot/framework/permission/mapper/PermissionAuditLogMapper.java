package com.auraboot.framework.permission.mapper;

import com.auraboot.framework.permission.entity.PermissionAuditLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Mapper for PermissionAuditLog.
 *
 * <p>Note: tenant_id is NOT auto-injected by TenantLineInterceptor for this table
 * because audit log writes happen in @Async context where MetaContext may be absent.
 * All queries explicitly include tenant_id.
 */
@Mapper
public interface PermissionAuditLogMapper extends BaseMapper<PermissionAuditLog> {

    /**
     * Insert an audit log entry using this mapper's explicit JSONB SQL contract.
     *
     * <p>This is a deliberate custom INSERT with {@code ::jsonb} cast, not a
     * general statement that BaseMapper is unsafe for JSONB. Entity-mapped
     * BaseMapper writes remain valid when {@code autoResultMap} and field
     * TypeHandlers are complete.
     */
    @Insert("""
            INSERT INTO ab_permission_audit_log
                (tenant_id, member_id, resource_code, action_code, record_id, record_pid, result, reason, evaluation_trace, created_at)
            VALUES
                (#{entry.tenantId}, #{entry.memberId}, #{entry.resourceCode}, #{entry.actionCode},
                 #{entry.recordId}, #{entry.recordPid}, #{entry.result}, #{entry.reason},
                 #{entry.evaluationTrace, typeHandler=com.auraboot.framework.application.database.mybatis.JsonbObjectListTypeHandler}::jsonb,
                 #{entry.createdAt})
            """)
    void insertAuditLog(@Param("entry") PermissionAuditLog entry);

    /**
     * Get recent audit log entries for a tenant, newest first.
     */
    @Select("""
            SELECT * FROM ab_permission_audit_log
            WHERE tenant_id = #{tenantId}
            ORDER BY created_at DESC
            LIMIT #{limit}
            """)
    @ResultMap("mybatis-plus_PermissionAuditLog")
    List<PermissionAuditLog> findRecent(
            @Param("tenantId") Long tenantId,
            @Param("limit") int limit);

    /**
     * Get audit log entries for a specific member.
     */
    @Select("""
            SELECT * FROM ab_permission_audit_log
            WHERE tenant_id = #{tenantId}
              AND member_id = #{memberId}
            ORDER BY created_at DESC
            LIMIT #{limit}
            """)
    @ResultMap("mybatis-plus_PermissionAuditLog")
    List<PermissionAuditLog> findByMember(
            @Param("tenantId") Long tenantId,
            @Param("memberId") Long memberId,
            @Param("limit") int limit);

    /**
     * Get audit log entries for a specific resource.
     */
    @Select("""
            SELECT * FROM ab_permission_audit_log
            WHERE tenant_id = #{tenantId}
              AND resource_code = #{resourceCode}
            ORDER BY created_at DESC
            LIMIT #{limit}
            """)
    @ResultMap("mybatis-plus_PermissionAuditLog")
    List<PermissionAuditLog> findByResource(
            @Param("tenantId") Long tenantId,
            @Param("resourceCode") String resourceCode,
            @Param("limit") int limit);

    /**
     * Get audit log entries that reference a Rule Center decision trace.
     */
    @Select("""
            SELECT * FROM ab_permission_audit_log
            WHERE tenant_id = #{tenantId}
              AND (
                evaluation_trace @> jsonb_build_array(
                    jsonb_build_object(
                        'details',
                        jsonb_build_object('ruleTraceId', #{traceId})
                    )
                  )
                OR EXISTS (
                    SELECT 1
                    FROM jsonb_array_elements(evaluation_trace) AS step(item)
                    CROSS JOIN LATERAL jsonb_array_elements(
                        COALESCE(step.item -> 'details' -> 'ruleCenterFailures', '[]'::jsonb)
                    ) AS failure(item)
                    WHERE failure.item ->> 'ruleTraceId' = #{traceId}
                )
              )
            ORDER BY created_at DESC
            LIMIT #{limit}
            """)
    @ResultMap("mybatis-plus_PermissionAuditLog")
    List<PermissionAuditLog> findByTraceId(
            @Param("tenantId") Long tenantId,
            @Param("traceId") String traceId,
            @Param("limit") int limit);
}
