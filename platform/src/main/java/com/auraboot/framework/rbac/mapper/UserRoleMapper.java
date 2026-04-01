package com.auraboot.framework.rbac.mapper;

import com.auraboot.framework.rbac.entity.UserRole;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * User-role association data access layer.
 * Phase 2: ab_user_role uses member_id (tenant_member.id) instead of user_id.
 */
@Mapper
public interface UserRoleMapper extends BaseMapper<UserRole> {

    /**
     * Find role associations by member ID
     */
    @Select("SELECT * FROM ab_user_role WHERE member_id = #{memberId} AND status = 'active' ORDER BY created_at DESC")
    List<UserRole> findByMemberId(@Param("memberId") Long memberId);

    /**
     * Find role associations by member ID and tenant ID
     */
    @Select("SELECT * FROM ab_user_role WHERE member_id = #{memberId} AND tenant_id = #{tenantId} AND status = 'active' AND deleted_flag = false")
    List<UserRole> findByMemberIdAndTenantId(@Param("memberId") Long memberId, @Param("tenantId") Long tenantId);

    /**
     * Find association by member ID, role ID, and tenant ID
     */
    @Select("SELECT * FROM ab_user_role WHERE member_id = #{memberId} AND role_id = #{roleId} AND tenant_id = #{tenantId} AND deleted_flag = false LIMIT 1")
    @InterceptorIgnore(tenantLine = "true")
    UserRole findByMemberIdAndRoleIdAndTenantId(@Param("memberId") Long memberId, @Param("roleId") Long roleId, @Param("tenantId") Long tenantId);

    /**
     * Find by PID
     */
    @Select("SELECT * FROM ab_user_role WHERE pid = #{pid} AND deleted_flag = false")
    UserRole findByPid(@Param("pid") String pid);

    /**
     * Soft-delete all role associations for a member in a tenant
     */
    @Delete("UPDATE ab_user_role SET deleted_flag = true, updated_at = CURRENT_TIMESTAMP WHERE member_id = #{memberId} AND tenant_id = #{tenantId}")
    int deleteByMemberIdAndTenantId(@Param("memberId") Long memberId, @Param("tenantId") Long tenantId);

    /**
     * Count role associations for a member in a tenant
     */
    @Select("SELECT COUNT(*) FROM ab_user_role WHERE member_id = #{memberId} AND tenant_id = #{tenantId} " +
            "AND status = 'active'  ")
    long countByMemberIdAndTenantId(@Param("memberId") Long memberId, @Param("tenantId") Long tenantId);

    /**
     * Get all user role info for a tenant (joins with ab_user and ab_role via member)
     */
    @Select("SELECT ur.member_id, u.user_name, u.nick_name, r.name as role_name, r.code as role_code " +
            "FROM ab_user_role ur " +
            "LEFT JOIN ab_tenant_member tm ON ur.member_id = tm.id " +
            "LEFT JOIN ab_user u ON tm.user_id = u.id " +
            "LEFT JOIN ab_role r ON ur.role_id = r.id " +
            "WHERE ur.tenant_id = #{tenantId} AND ur.status = 'active' AND ur.deleted_flag = false " +
            "AND u.deleted_flag = false AND r.deleted_flag = false " +
            "ORDER BY u.user_name, r.name")
    List<Map<String, Object>> getTenantUserRoles(@Param("tenantId") Long tenantId);

    @Select("SELECT COUNT(*) FROM ab_user_role WHERE tenant_id = #{tenantId}  ")
    int countByTenantId(@Param("tenantId") Long tenantId);

    /**
     * Get all role IDs for a member
     */
    @Select("SELECT DISTINCT role_id FROM ab_user_role " +
            "WHERE member_id = #{memberId} AND status = 'active' AND deleted_flag = false")
    List<Long> findRoleIdsByMemberId(@Param("memberId") Long memberId);

    /**
     * Get all member IDs for a role
     */
    @Select("SELECT DISTINCT member_id FROM ab_user_role " +
            "WHERE role_id = #{roleId} AND status = 'active' AND deleted_flag = false")
    List<Long> findMemberIdsByRoleId(@Param("roleId") Long roleId);
}
