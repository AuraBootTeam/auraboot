package com.auraboot.framework.rbac.mapper;

import com.auraboot.framework.rbac.entity.Role;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 角色数据访问层
 */
@Mapper
public interface RoleMapper extends BaseMapper<Role> {

    /**
     * 根据角色编码查询角色
     */
    @Select("SELECT * FROM ab_role WHERE code = #{code} AND deleted_flag = false")
    Role findByCode(@Param("code") String code);

    /**
     * 根据角色PID查询角色
     */
    @Select("SELECT * FROM ab_role WHERE pid = #{pid} AND deleted_flag = false")
    Role findByPid(@Param("pid") String pid);

    /**
     * 根据租户ID查询角色列表
     */
    @Select("SELECT * FROM ab_role WHERE tenant_id = #{tenantId} AND deleted_flag = false ORDER BY priority ASC, created_at DESC")
    List<Role> findByTenantId(@Param("tenantId") Long tenantId);

//    /**
//     * 查询系统角色列表
//     */
//    @Select("SELECT * FROM ab_role WHERE is_system = true   ORDER BY priority ASC")
//    List<Role> findSystemRoles();

//    /**
//     * 根据角色类型查询角色列表
//     */
//    @Select("SELECT * FROM ab_role WHERE type = #{type}   ORDER BY priority ASC, created_at DESC")
//    List<Role> findByType(@Param("type") String type);

    /**
     * 根据租户ID和角色类型查询角色列表
     */
    @Select("SELECT * FROM ab_role WHERE type = #{type} AND deleted_flag = false ORDER BY priority ASC")
    List<Role> findByTenantIdAndType(@Param("tenantId") Long tenantId, @Param("type") String type);

//    /**
//     * 查询默认角色
//     */
//    @Select("SELECT * FROM ab_role WHERE  is_default = true   LIMIT 1")
//    Role findDefaultByTenantId(@Param("tenantId") Long tenantId);

    /**
     * 查询默认角色
     */
    @Select("SELECT * FROM ab_role WHERE is_default = true AND deleted_flag = false LIMIT 1")
    Role findDefaultRole(@Param("tenantId") Long tenantId);

//    /**
//     * 根据用户ID查询用户角色列表
//     */
//    @Select("SELECT r.* FROM ab_role r INNER JOIN ab_user_role ur ON r.id = ur.role_id " +
//            "WHERE ur.user_id = #{userId} AND ur.status = 'active' AND r.deleted_flag = false AND ur.deleted_flag = false " +
//            "ORDER BY r.priority ASC")
//    List<Role> findByUserId(@Param("userId") String userId);
//
//    /**
//     * 根据用户ID查询用户角色列表
//     */
//    @Select("SELECT r.* FROM ab_role r INNER JOIN ab_user_role ur ON r.id = ur.role_id " +
//            "WHERE ur.user_id = #{userId} AND ur.status = 'active' AND r.deleted_flag = false AND ur.deleted_flag = false " +
//            "ORDER BY r.priority ASC")
//    List<Role> findUserRoles(@Param("userId") Long userId);

    /**
     * Find roles for a member in a tenant.
     */
    @Select("SELECT r.* FROM ab_role r INNER JOIN ab_user_role ur ON r.id = ur.role_id " +
            "WHERE ur.member_id = #{memberId} AND ur.tenant_id = #{tenantId} AND ur.status = 'active' " +
            "AND r.deleted_flag = false AND ur.deleted_flag = false ORDER BY r.priority ASC")
    List<Role> findByMemberIdAndTenantId(@Param("memberId") Long memberId, @Param("tenantId") Long tenantId);

//    /**
//     * 根据用户ID和租户ID查找用户角色
//     */
//    @Select("SELECT r.* FROM ab_role r INNER JOIN ab_user_role ur ON r.id = ur.role_id WHERE ur.user_id = #{userId} AND ur.tenant_id = #{tenantId}")
//    List<Role> findUserRolesByTenant(@Param("userId") Long userId, @Param("tenantId") Long tenantId);

    /**
     * 根据租户ID统计角色数量
     */
    @Select("SELECT COUNT(*) FROM ab_role WHERE tenant_id = #{tenantId} AND deleted_flag = false")
    long countByTenantId(@Param("tenantId") Long tenantId);

    /**
     * 统计租户角色数量
     */
    @Select("SELECT COUNT(*) FROM ab_role WHERE status = #{status} AND deleted_flag = false")
    long countByTenantIdAndStatus(@Param("tenantId") Long tenantId, @Param("status") String status);

    /**
     * 检查角色编码是否存在
     * @param tenantId 租户ID
     * @param code 角色编码
     * @return 是否存在
     */
    @Select("SELECT COUNT(*) > 0 FROM ab_role WHERE tenant_id = #{tenantId} AND code = #{code} AND deleted_flag = false")
    boolean existsByCode(@Param("tenantId") Long tenantId, @Param("code") String code);

    /**
     * 根据编码查询角色ID
     * @param tenantId 租户ID
     * @param code 角色编码
     * @return 角色ID
     */
    @Select("SELECT id FROM ab_role WHERE tenant_id = #{tenantId} AND code = #{code} AND deleted_flag = false")
    Long findIdByCode(@Param("tenantId") Long tenantId, @Param("code") String code);

    /**
     * 根据编码查询角色PID
     * @param tenantId 租户ID
     * @param code 角色编码
     * @return 角色PID
     */
    @Select("SELECT pid FROM ab_role WHERE tenant_id = #{tenantId} AND code = #{code} AND deleted_flag = false")
    String findPidByCode(@Param("tenantId") Long tenantId, @Param("code") String code);

    // ==================== Plugin Import Support ====================

    /**
     * Update role fields for plugin import.
     */
    @Update("""
        UPDATE ab_role SET
            name = #{name}, description = #{description}, type = #{type},
            priority = #{priority}, is_default = #{isDefault}, is_system = #{isSystem},
            scope_type = #{scopeType}, scope_content = #{scopeContent}::jsonb,
            plugin_pid = #{pluginPid}, updated_at = NOW()
        WHERE tenant_id = #{tenantId} AND code = #{code} AND deleted_flag = FALSE
        """)
    int updateForPluginImport(@Param("name") String name,
                              @Param("description") String description,
                              @Param("type") String type,
                              @Param("priority") Integer priority,
                              @Param("isDefault") Boolean isDefault,
                              @Param("isSystem") Boolean isSystem,
                              @Param("scopeType") String scopeType,
                              @Param("scopeContent") String scopeContent,
                              @Param("pluginPid") String pluginPid,
                              @Param("tenantId") Long tenantId,
                              @Param("code") String code);

    /**
     * Update plugin_pid by id.
     */
    @Update("UPDATE ab_role SET plugin_pid = #{pluginPid} WHERE id = #{id}")
    int updatePluginPidById(@Param("pluginPid") String pluginPid, @Param("id") Long id);

    /**
     * Soft delete role by pid (fallback delete for plugin uninstall).
     */
    @Update("UPDATE ab_role SET deleted_flag = TRUE, updated_at = NOW() WHERE pid = #{pid}")
    int softDeleteByPid(@Param("pid") String pid);

    /**
     * Batch lookup of role priorities by id (M-090 CFG-001).
     * Skips soft-deleted rows so the mobile config role layer doesn't
     * apply overrides from removed roles.
     *
     * Returns a list of rows each containing {@code id} and {@code priority}.
     * Callers convert to {@code Map<Long, Integer>} via stream if needed.
     *
     * NOTE: Guard at the call site with {@code if (ids.isEmpty()) return List.of();}
     * before invoking — MyBatis {@code <foreach>} over an empty collection produces
     * {@code IN ()} which Postgres rejects.
     */
    @Select("""
        <script>
        SELECT id AS id, priority AS priority FROM ab_role
        WHERE id IN
        <foreach item="id" collection="ids" open="(" separator="," close=")">
            #{id}
        </foreach>
          AND (deleted_flag = false OR deleted_flag IS NULL)
        </script>
        """)
    List<Map<String, Object>> findPrioritiesByIds(@Param("ids") Set<Long> ids);
}