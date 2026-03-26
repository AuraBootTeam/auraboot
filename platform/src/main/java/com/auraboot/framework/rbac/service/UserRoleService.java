package com.auraboot.framework.rbac.service;

import com.auraboot.framework.rbac.entity.UserRole;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;
import java.util.Map;

/**
 * 用户角色关联服务接口
 */
public interface UserRoleService extends IService<UserRole> {

    /**
     * 为用户分配角色
     */
    boolean assignRolesToUser(Long userId, List<Long> roleIds, Long tenantId, Long operatorId);

    /**
     * 移除用户的角色
     */
    boolean removeRolesFromUser(Long userId, List<Long> roleIds, Long tenantId);

    /**
     * 移除用户的单个角色
     */
    boolean removeUserRole(Long userId, Long roleId, Long tenantId);


    /**
     * 移除用户在指定租户下的所有角色
     */
    boolean removeAllRolesFromUserInTenant(Long userId, Long tenantId);



    /**
     * 根据用户ID和租户ID查询角色关联列表
     */
    List<UserRole> findByUserIdAndTenantId(Long userId, Long tenantId);

    /**
     * 根据用户ID、角色ID和租户ID查询关联关系
     */
    UserRole findByUserIdAndRoleIdAndTenantId(Long userId, Long roleId, Long tenantId);

    /**
     * 根据PID查询用户角色关联
     */
    UserRole findByPid(String pid);



    /**
     * 分页查询用户角色关联列表
     */
    Page<UserRole> findUserRoles(int pageNum, int pageSize, Long userId, Long roleId, Long tenantId, Long storeId);




    /**
     * 统计用户角色数量
     */
    long countByUserId(Long userId);

    /**
     * 统计角色被分配的用户数量
     */
    long countByRoleId(Long roleId);

    /**
     * 统计租户下的用户角色数量
     */
    long countByTenantId(Long tenantId);

    /**
     * 批量分配角色给用户
     */
    int batchAssignRoles(List<UserRole> userRoles);

    /**
     * 批量移除用户角色
     */
    int batchRemoveRoles(List<Long> userRoleIds);

    /**
     * 复制用户角色到另一个用户
     */
    boolean copyUserRoles(Long sourceUserId, Long targetUserId, Long tenantId);



    /**
     * 同步用户角色
     * 根据提供的角色ID列表，同步用户在指定租户下的角色配置
     */
    boolean syncUserRoles(Long userId, List<Long> roleIds, Long tenantId, Long operatorId);



    /**
     * 获取用户在指定租户下的角色ID列表
     */
    List<Long> getRoleIdsByUserIdAndTenantId(Long userId, Long tenantId);



    /**
     * 检查角色是否被任何用户使用
     */
    boolean isRoleInUse(Long roleId);

    /**
     * 检查角色在指定租户下是否被任何用户使用
     */
    boolean isRoleInUseInTenant(Long roleId, Long tenantId);


    /**
     * 获取租户下所有用户的角色信息
     */
    List<Map<String, Object>> getTenantUserRoles(Long tenantId);

    /**
     * 验证用户角色配置
     */
    Map<String, Object> validateUserRoles(Long userId, Long tenantId);

    /**
     * 清理无效的用户角色关联
     */
    int cleanupInvalidUserRoles();

    /**
     * 根据多个用户ID查询角色关联列表
     */
    List<UserRole> findByUserIds(List<Long> userIds);

    /**
     * 根据多个角色ID查询用户关联列表
     */
    List<UserRole> findByRoleIds(List<Long> roleIds);

    /**
     * 获取用户角色变更历史
     */
    List<Map<String, Object>> getUserRoleHistory(Long userId, Long tenantId, int days);

    /**
     * 转移用户角色到新租户
     */
    boolean transferUserRolesToTenant(Long userId, Long fromTenantId, Long toTenantId);

    /**
     * 激活用户角色
     */
    boolean activateUserRole(Long userRoleId);

    /**
     * 停用用户角色
     */
    boolean deactivateUserRole(Long userRoleId);

    /**
     * 批量激活用户角色
     */
    int batchActivateUserRoles(List<Long> userRoleIds);

    /**
     * 批量停用用户角色
     */
    int batchDeactivateUserRoles(List<Long> userRoleIds);
}