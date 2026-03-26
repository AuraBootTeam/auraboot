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
 * 用户角色关联数据访问层
 */
@Mapper
public interface UserRoleMapper extends BaseMapper<UserRole> {

//    /**
//     * 根据用户ID查询角色关联列表
//     */
//    @Select("SELECT * FROM ab_user_role WHERE user_id = #{userId}   ORDER BY created_at DESC")
//    List<UserRole> findByUserId(@Param("userId") String userId);
//
//    /**
//     * 根据角色ID查询用户关联列表
//     */
//    @Select("SELECT * FROM ab_user_role WHERE role_id = #{roleId}   ORDER BY created_at DESC")
//    List<UserRole> findByRoleId(@Param("roleId") Long roleId);

//    /**
//     * 根据租户ID查询用户角色关联列表
//     */
//    @Select("SELECT * FROM ab_user_role WHERE tenant_id = #{tenantId}   ORDER BY created_at DESC")
//    List<UserRole> findByTenantId(@Param("tenantId") Long tenantId);

    /**
     * 根据用户ID查询角色关联列表
     */
    @Select("SELECT * FROM ab_user_role WHERE user_id = #{userId} AND status = 'active' ORDER BY created_at DESC")
    List<UserRole> findByUserId(@Param("userId") Long userId);

    /**
     * 根据用户ID和租户ID查询角色关联列表
     */
    @Select("SELECT * FROM ab_user_role WHERE user_id = #{userId} AND tenant_id = #{tenantId} AND status = 'active' AND deleted_flag = false")
    List<UserRole> findByUserIdAndTenantId(@Param("userId") Long userId, @Param("tenantId") Long tenantId);

    /**
     * 根据用户ID、角色ID和租户ID查询关联关系
     */
    @Select("SELECT * FROM ab_user_role WHERE user_id = #{userId} AND role_id = #{roleId} AND tenant_id = #{tenantId} AND deleted_flag = false LIMIT 1")
    @InterceptorIgnore(tenantLine = "true")
    UserRole findByUserIdAndRoleIdAndTenantId(@Param("userId") Long userId, @Param("roleId") Long roleId, @Param("tenantId") Long tenantId);

    /**
     * 根据PID查询用户角色关联
     */
    @Select("SELECT * FROM ab_user_role WHERE pid = #{pid} AND deleted_flag = false")
    UserRole findByPid(@Param("pid") String pid);

//    /**
//     * 根据门店ID查询用户角色关联列表
//     */
//    @Select("SELECT * FROM ab_user_role WHERE store_id = #{storeId} AND status = 'active'  ")
//    List<UserRole> findByStoreId(@Param("storeId") Long storeId);

//    /**
//     * 根据用户ID和门店ID查询角色关联列表
//     */
//    @Select("SELECT * FROM ab_user_role WHERE user_id = #{userId} AND store_id = #{storeId} AND status = 'active'  ")
//    List<UserRole> findByUserIdAndStoreId(@Param("userId") String userId, @Param("storeId") Long storeId);

//    /**
//     * 批量删除用户的所有角色关联
//     */
//    @Delete("UPDATE ab_user_role SET deleted_flag = true, updated_at = CURRENT_TIMESTAMP WHERE user_id = #{userId}")
//    int deleteByUserId(@Param("userId") String userId);

    /**
     * 批量删除用户在指定租户下的所有角色关联
     */
    @Delete("UPDATE ab_user_role SET deleted_flag = true, updated_at = CURRENT_TIMESTAMP WHERE user_id = #{userId} AND tenant_id = #{tenantId}")
    int deleteByUserIdAndTenantId(@Param("userId") Long userId, @Param("tenantId") Long tenantId);

//    /**
//     * 检查用户是否拥有指定角色
//     */
//    @Select("SELECT COUNT(*) > 0 FROM ab_user_role WHERE user_id = #{userId} AND role_id = #{roleId} " +
//            "AND status = 'active'  ")
//    boolean hasRole(@Param("userId") String userId, @Param("roleId") Long roleId);

//    /**
//     * 检查用户在指定租户下是否拥有指定角色
//     */
//    @Select("SELECT COUNT(*) > 0 FROM ab_user_role WHERE user_id = #{userId} AND role_id = #{roleId} AND tenant_id = #{tenantId} " +
//            "AND status = 'active'  ")
//    boolean hasRoleInTenant(@Param("userId") String userId, @Param("roleId") Long roleId, @Param("tenantId") Long tenantId);

    /**
     * 统计用户角色数量
     */
    @Select("SELECT COUNT(*) FROM ab_user_role WHERE user_id = #{userId} AND tenant_id = #{tenantId} " +
            "AND status = 'active'  ")
    long countByUserIdAndTenantId(@Param("userId") Long userId, @Param("tenantId") Long tenantId);

//    /**
//     * 获取用户在所有租户中的角色信息
//     */
//    @Select("SELECT ur.tenant_id, t.name as tenant_name, r.name as role_name, r.code as role_code " +
//            "FROM ab_user_role ur " +
//            "LEFT JOIN ab_tenant t ON ur.tenant_id = t.id " +
//            "LEFT JOIN ab_role r ON ur.role_id = r.id " +
//            "WHERE ur.user_id = #{userId} AND ur.status = 'active' AND ur.deleted_flag = false " +
//            "AND t.deleted_flag = false AND r.deleted_flag = false " +
//            "ORDER BY t.name, r.name")
//    List<Map<String, Object>> getUserRolesInAllTenants(@Param("userId") Long userId);

    /**
     * 获取租户下的所有用户角色信息
     */
    @Select("SELECT ur.user_id, u.user_name, u.nick_name, r.name as role_name, r.code as role_code " +
            "FROM ab_user_role ur " +
            "LEFT JOIN ab_user u ON ur.user_id = u.id " +
            "LEFT JOIN ab_role r ON ur.role_id = r.id " +
            "WHERE ur.tenant_id = #{tenantId} AND ur.status = 'active' AND ur.deleted_flag = false " +
            "AND u.deleted_flag = false AND r.deleted_flag = false " +
            "ORDER BY u.user_name, r.name")
    List<Map<String, Object>> getTenantUserRoles(@Param("tenantId") Long tenantId);

    @Select("SELECT COUNT(*) FROM ab_user_role WHERE tenant_id = #{tenantId}  ")
    int countByTenantId(@Param("tenantId") Long tenantId);
    
    /**
     * Get all role IDs for a user
     * 
     * @param userId User ID
     * @return List of role IDs
     */
    @Select("SELECT DISTINCT role_id FROM ab_user_role " +
            "WHERE user_id = #{userId} AND status = 'active' AND deleted_flag = false")
    List<Long> findRoleIdsByUserId(@Param("userId") Long userId);
    
    /**
     * Get all user IDs for a role
     * 
     * @param roleId Role ID
     * @return List of user IDs
     */
    @Select("SELECT DISTINCT user_id FROM ab_user_role " +
            "WHERE role_id = #{roleId} AND status = 'active' AND deleted_flag = false")
    List<Long> findUserIdsByRoleId(@Param("roleId") Long roleId);
}
