package com.auraboot.framework.rbac.service;

import com.auraboot.framework.rbac.entity.Role;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;
import java.util.Map;

/**
 * 角色服务接口
 */
public interface RoleService extends IService<Role> {

    /**
     * 创建角色
     */
    Role createRole(Role role);

    /**
     * 更新角色信息
     */
    Role updateRole(Role role);

    /**
     * 根据角色PID查询角色
     */
    Role findByPid(String pid);



    /**
     * 根据租户ID查询角色列表
     */
    List<Role> findByTenantId(Long tenantId);




    /**
     * 根据租户ID和角色类型查询角色列表
     */
    List<Role> findByTenantIdAndType(Long tenantId, String type);

    /**
     * 分页查询角色列表
     */
    Page<Role> findRoles(int pageNum, int pageSize, Long tenantId, String keyword, String type, String status);

    /**
     * 查询默认角色
     */
    Role findDefaultRole(Long tenantId);


    /**
     * 启用角色
     */
    boolean enableRole(Long roleId);

    /**
     * 禁用角色
     */
    boolean disableRole(Long roleId);

    /**
     * 删除角色(逻辑删除)
     */
    boolean deleteRole(Long roleId);

    /**
     * 检查角色编码是否可用
     */
    boolean isCodeAvailable(String code, Long tenantId);

    /**
     * Assign permissions to a role
     */
    boolean assignPermissions(Long roleId, List<Long> permissionIds);

    /**
     * Remove permissions from a role
     */
    boolean removePermissions(Long roleId, List<Long> permissionIds);

    /**
     * Get all permission IDs for a role
     */
    List<Long> getRolePermissionIds(Long roleId);

    /**
     * 为用户分配角色
     */
    boolean assignRoleToUser(Long userId, Long roleId, Long tenantId, Long storeId);

    /**
     * 移除用户角色
     */
    boolean removeRoleFromUser(Long userId, Long roleId, Long tenantId);

    /**
     * 统计租户角色数量
     */
    long countByTenantId(Long tenantId);

    /**
     * 复制角色
     */
    Role copyRole(Long roleId, String newName, String newCode);

    /**
     * 创建租户默认角色
     */
    void createDefaultRolesForTenant(Long tenantId);

    /**
     * 获取角色统计信息
     */
    Map<String, Object> getRoleStatistics(Long tenantId);



    /**
     * 获取角色层级结构
     */
    List<Map<String, Object>> getRoleHierarchy(Long tenantId);
    
    /**
     * 初始化系统默认角色
     */
    void initializeSystemRoles();
    
    // 别名方法，用于兼容不同的命名约定
    default Role getRoleById(Long roleId) {
        return getById(roleId);
    }
    



    default List<Role> getRolesByTenantId(Long tenantId) {
        return findByTenantId(tenantId);
    }
    


}