package com.auraboot.framework.rbac.service;

import com.auraboot.framework.rbac.entity.UserRole;
import com.auraboot.framework.rbac.dto.UserRoleResponse;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;
import java.util.Map;

/**
 * User-role association service interface.
 * Phase 2: all subject references use memberId (tenant_member.id).
 */
public interface UserRoleService extends IService<UserRole> {

    /**
     * Assign roles to a member
     */
    boolean assignRolesToMember(Long memberId, List<Long> roleIds, Long tenantId, Long operatorId);

    /**
     * Assign roles to a member using public member and role PIDs.
     */
    boolean assignRolesToMemberByRolePids(String memberPid, List<String> rolePids, Long tenantId, Long operatorId);

    /**
     * Assign roles to a member using a public member PID and stable role codes.
     */
    boolean assignRolesToMemberByRoleCodes(String memberPid, List<String> roleCodes, Long tenantId, Long operatorId);

    /**
     * Remove roles from a member
     */
    boolean removeRolesFromMember(Long memberId, List<Long> roleIds, Long tenantId);

    /**
     * Remove a single role from a member
     */
    boolean removeMemberRole(Long memberId, Long roleId, Long tenantId);

    /**
     * Remove all roles from a member in a tenant
     */
    boolean removeAllRolesFromMemberInTenant(Long memberId, Long tenantId);

    /**
     * Find role associations by member ID and tenant ID
     */
    List<UserRole> findByMemberIdAndTenantId(Long memberId, Long tenantId);

    /**
     * Find association by member ID, role ID, and tenant ID
     */
    UserRole findByMemberIdAndRoleIdAndTenantId(Long memberId, Long roleId, Long tenantId);

    /**
     * Find by PID
     */
    UserRole findByPid(String pid);

    /**
     * Paginated query
     */
    Page<UserRole> findUserRoles(int pageNum, int pageSize, Long memberId, Long roleId, Long tenantId, Long storeId);

    /**
     * Paginated public query. Response uses PIDs only.
     */
    Page<UserRoleResponse> findUserRoleResponses(
            int pageNum,
            int pageSize,
            String memberPid,
            String rolePid,
            Long legacyMemberId,
            Long legacyRoleId,
            Long tenantId,
            Long storeId);

    /**
     * Count roles for a member
     */
    long countByMemberId(Long memberId);

    /**
     * Count members for a role
     */
    long countByRoleId(Long roleId);

    /**
     * Count user-role associations in a tenant
     */
    long countByTenantId(Long tenantId);

    /**
     * Batch assign roles
     */
    int batchAssignRoles(List<UserRole> userRoles);

    /**
     * Batch remove roles
     */
    int batchRemoveRoles(List<Long> userRoleIds);

    /**
     * Copy roles from one member to another
     */
    boolean copyMemberRoles(Long sourceMemberId, Long targetMemberId, Long tenantId);

    /**
     * Sync member roles
     */
    boolean syncMemberRoles(Long memberId, List<Long> roleIds, Long tenantId, Long operatorId);

    /**
     * Get role IDs for a member in a tenant
     */
    List<Long> getRoleIdsByMemberIdAndTenantId(Long memberId, Long tenantId);

    /**
     * Get role PIDs for a member PID in a tenant.
     */
    List<String> getRolePidsByMemberPidAndTenantId(String memberPid, Long tenantId);

    /**
     * Get member-role assignments for a role PID in a tenant.
     */
    List<UserRoleResponse> findRoleMemberResponsesByRolePid(String rolePid, Long tenantId);

    /**
     * Validate member roles using a public member PID.
     */
    Map<String, Object> validateMemberRolesByPid(String memberPid, Long tenantId);

    /**
     * Check if a role is in use
     */
    boolean isRoleInUse(Long roleId);

    /**
     * Check if a role is in use in a tenant
     */
    boolean isRoleInUseInTenant(Long roleId, Long tenantId);

    /**
     * Get all user role info for a tenant
     */
    List<Map<String, Object>> getTenantUserRoles(Long tenantId);

    /**
     * Validate member roles
     */
    Map<String, Object> validateMemberRoles(Long memberId, Long tenantId);

    /**
     * Cleanup invalid user-role associations
     */
    int cleanupInvalidUserRoles();

    /**
     * Find by multiple member IDs
     */
    List<UserRole> findByMemberIds(List<Long> memberIds);

    /**
     * Find by multiple role IDs
     */
    List<UserRole> findByRoleIds(List<Long> roleIds);

    /**
     * Get member role history
     */
    List<Map<String, Object>> getMemberRoleHistory(Long memberId, Long tenantId, int days);

    /**
     * Transfer member roles to a new tenant
     */
    boolean transferMemberRolesToTenant(Long memberId, Long fromTenantId, Long toTenantId);

    /**
     * Activate a user-role association
     */
    boolean activateUserRole(Long userRoleId);

    /**
     * Deactivate a user-role association
     */
    boolean deactivateUserRole(Long userRoleId);

    /**
     * Batch activate user-role associations
     */
    int batchActivateUserRoles(List<Long> userRoleIds);

    /**
     * Batch deactivate user-role associations
     */
    int batchDeactivateUserRoles(List<Long> userRoleIds);
}
