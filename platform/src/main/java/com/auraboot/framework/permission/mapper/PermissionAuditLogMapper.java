package com.auraboot.framework.permission.mapper;

import com.auraboot.framework.permission.entity.PermissionAuditLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
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
     * Get recent audit log entries for a tenant, newest first.
     */
    @Select("""
            SELECT * FROM ab_permission_audit_log
            WHERE tenant_id = #{tenantId}
            ORDER BY created_at DESC
            LIMIT #{limit}
            """)
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
    List<PermissionAuditLog> findByResource(
            @Param("tenantId") Long tenantId,
            @Param("resourceCode") String resourceCode,
            @Param("limit") int limit);
}
