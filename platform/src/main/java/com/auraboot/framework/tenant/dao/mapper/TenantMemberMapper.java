package com.auraboot.framework.tenant.dao.mapper;

import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 租户成员数据访问层
 */
@Mapper
public interface TenantMemberMapper extends BaseMapper<TenantMember> {

    /**
     * 根据租户ID查询成员列表
     */
    @Select("SELECT * FROM ab_tenant_member WHERE tenant_id = #{tenantId} AND deleted_flag = FALSE ORDER BY created_at DESC")
    List<TenantMember> findByTenantId(@Param("tenantId") Long tenantId);

    /**
     * 根据用户ID查询所有租户ID
     * @param userId 用户ID
     * @return 租户ID列表
     */
    @Select("SELECT tenant_id FROM ab_tenant_member WHERE user_id = #{userId} AND status = 'active' AND deleted_flag = FALSE ORDER BY created_at DESC")
    List<Long> getTenantIdsByUserId(@Param("userId") Long userId);

    /**
     * 根据用户ID查询租户成员关系
     * @param userId 用户ID
     * @return 租户成员列表
     */
    @Select("SELECT * FROM ab_tenant_member WHERE user_id = #{userId} AND status = 'active' AND deleted_flag = FALSE ORDER BY created_at DESC")
    List<TenantMember> findByUserId(@Param("userId") Long userId);

    /**
     * 根据租户ID和用户ID查询成员关系
     */
    @Select("""
            SELECT *
            FROM ab_tenant_member
            WHERE tenant_id = #{tenantId}
              AND user_id = #{userId}
              AND deleted_flag = FALSE
            LIMIT 1
            """)
    TenantMember findByTenantIdAndUserId(@Param("tenantId") Long tenantId, @Param("userId") Long userId);


//    /**
//     * 根据成员类型查询成员列表
//     */
//    @Select("SELECT * FROM ab_tenant_member WHERE  member_type = #{memberType}   ORDER BY created_at DESC")
//    List<TenantMember> findByTenantIdAndMemberType(@Param("tenantId") Long tenantId, @Param("memberType") String memberType);

    /**
     * 统计租户成员数量
     */
    @Select("""
            SELECT COUNT(*)
            FROM ab_tenant_member
            WHERE tenant_id = #{tenantId}
              AND status = #{status}
              AND deleted_flag = FALSE
            """)
    long countByTenantIdAndStatus(@Param("tenantId") Long tenantId, @Param("status") String status);

    /**
     * Count user role assignments in a specific tenant.
     * Used to prefer tenants with active role bindings during login.
     */
    @Select("""
            SELECT COUNT(*)
            FROM ab_user_role
            WHERE user_id = #{userId}
              AND tenant_id = #{tenantId}
              AND (deleted_flag = FALSE OR deleted_flag IS NULL)
            """)
    long countUserRolesInTenant(@Param("userId") Long userId, @Param("tenantId") Long tenantId);

//    /**
//     * 查询成员详情(包含用户信息)
//     */
//    @Select("SELECT tm.*, u.user_name, u.nick_name, u.email, u.mobile " +
//            "FROM ab_tenant_member tm LEFT JOIN ab_user u ON tm.user_id = u.id " +
//            "WHERE tm.tenant_id = #{tenantId} AND tm.deleted_flag = false ORDER BY tm.created_at DESC")
//    List<TenantMember> findMembersWithUserInfo(@Param("tenantId") Long tenantId);

//    /**
//     * 检查用户是否为租户成员
//     */
//    @Select("SELECT COUNT(*) > 0 FROM ab_tenant_member WHERE  user_id = #{userId} AND status = 'active'  ")
//    boolean isTenantMember(@Param("tenantId") Long tenantId, @Param("userId") Long userId);

    /**
     * Get tenant name by ID. Used to identify System Tenant during login resolution.
     */
    @Select("SELECT name FROM ab_tenant WHERE id = #{tenantId} AND (deleted_flag = FALSE OR deleted_flag IS NULL)")
    String getTenantNameById(@Param("tenantId") Long tenantId);
}
