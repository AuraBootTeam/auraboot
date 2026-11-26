package com.auraboot.framework.tenant.dao.mapper;

import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 租户成员数据访问层
 */
@Mapper
public interface TenantMemberMapper extends BaseMapper<TenantMember> {

    /** Only owner identities for the actor's department filter. No employee record fields are returned. */
    @Select("""
            WITH RECURSIVE department_scope(pid, tenant_id) AS (
                SELECT d.pid, d.tenant_id
                FROM ab_tenant_member actor
                JOIN mt_org_employee e ON e.tenant_id = actor.tenant_id
                    AND e.org_emp_member_id = actor.pid AND e.deleted_flag = FALSE
                JOIN mt_org_department d ON d.tenant_id = actor.tenant_id
                    AND d.pid = e.org_emp_dept_id AND d.deleted_flag = FALSE
                WHERE actor.tenant_id = #{tenantId} AND actor.id = #{memberId}
                    AND actor.user_id = #{userId} AND actor.status = 'active'
                    AND actor.deleted_flag = FALSE
                UNION
                SELECT child.pid, child.tenant_id
                FROM mt_org_department child
                JOIN department_scope parent ON child.org_dept_parent_id = parent.pid
                    AND child.tenant_id = parent.tenant_id
                WHERE child.tenant_id = #{tenantId} AND child.deleted_flag = FALSE
                    AND #{includeSubDepartments}
            )
            SELECT DISTINCT u.pid
            FROM department_scope d
            JOIN mt_org_employee e ON e.tenant_id = d.tenant_id
                AND e.org_emp_dept_id = d.pid AND e.deleted_flag = FALSE
            JOIN ab_user u ON u.pid = e.org_emp_user_id AND u.deleted_flag = FALSE
            JOIN ab_tenant_member member ON member.tenant_id = #{tenantId}
                AND member.user_id = u.id AND member.status = 'active' AND member.deleted_flag = FALSE
            ORDER BY u.pid
            """)
    List<String> findDepartmentOwnerPids(@Param("tenantId") Long tenantId,
                                        @Param("memberId") Long memberId,
                                        @Param("userId") Long userId,
                                        @Param("includeSubDepartments") boolean includeSubDepartments);

    /**
     * 根据租户ID查询成员列表
     */
    @Select("SELECT * FROM ab_tenant_member WHERE tenant_id = #{tenantId} AND deleted_flag = FALSE ORDER BY created_at DESC")
    List<TenantMember> findByTenantId(@Param("tenantId") Long tenantId);

    /**
     * Page tenant members through their associated user projection. Keyword filtering belongs in
     * this query (rather than after pagination), otherwise the member picker can return unrelated
     * recent members and miss an exact email outside the first page.
     */
    @Select("""
            <script>
            SELECT tm.*
            FROM ab_tenant_member tm
            INNER JOIN ab_user u ON u.id = tm.user_id AND u.deleted_flag = FALSE
            WHERE tm.tenant_id = #{tenantId}
              AND tm.deleted_flag = FALSE
            <if test="status != null and status != ''">
              AND tm.status = #{status}
            </if>
            <if test="memberType != null and memberType != ''">
              AND COALESCE(u.user_type, 'human') = #{memberType}
            </if>
            <if test="keyword != null and keyword != ''">
              AND (
                LOWER(COALESCE(u.nick_name, '')) LIKE CONCAT('%', LOWER(#{keyword}), '%')
                OR LOWER(COALESCE(u.user_name, '')) LIKE CONCAT('%', LOWER(#{keyword}), '%')
                OR LOWER(COALESCE(u.email, '')) LIKE CONCAT('%', LOWER(#{keyword}), '%')
              )
            </if>
            ORDER BY tm.created_at DESC
            </script>
            """)
    Page<TenantMember> searchMembersPage(
            Page<TenantMember> page,
            @Param("tenantId") Long tenantId,
            @Param("keyword") String keyword,
            @Param("memberType") String memberType,
            @Param("status") String status);

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

    /** Resolve an active tenant member from the stable public PID used by reference fields. */
    @Select("""
            SELECT tm.*
            FROM ab_tenant_member tm
            INNER JOIN ab_user u ON u.id = tm.user_id
            WHERE tm.tenant_id = #{tenantId}
              AND u.pid = #{userPid}
              AND LOWER(tm.status) = 'active'
              AND tm.deleted_flag = FALSE
              AND u.deleted_flag = FALSE
            LIMIT 1
            """)
    TenantMember findActiveByTenantIdAndUserPid(
            @Param("tenantId") Long tenantId,
            @Param("userPid") String userPid);

    /** Resolve one replacement audience in one database statement. */
    @Select("""
            <script>
            SELECT u.pid AS user_pid, tm.pid AS member_pid, tm.id AS member_id
            FROM ab_tenant_member tm
            INNER JOIN ab_user u ON u.id = tm.user_id
            WHERE tm.tenant_id = #{tenantId}
              AND u.pid IN
              <foreach collection="userPids" item="pid" open="(" separator="," close=")">
                 #{pid}
              </foreach>
              AND LOWER(tm.status) = 'active'
              AND tm.deleted_flag = FALSE
              AND u.deleted_flag = FALSE
            </script>
            """)
    @ConstructorArgs({
        @Arg(column = "user_pid", javaType = String.class),
        @Arg(column = "member_pid", javaType = String.class),
        @Arg(column = "member_id", javaType = Long.class)
    })
    List<ActiveMemberRecipient> findActiveRecipients(
            @Param("tenantId") Long tenantId,
            @Param("userPids") List<String> userPids);

    record ActiveMemberRecipient(String userPid, String memberPid, Long memberId) {}

    /**
     * Find a tenant member by public PID within a tenant.
     */
    @Select("""
            SELECT *
            FROM ab_tenant_member
            WHERE tenant_id = #{tenantId}
              AND pid = #{pid}
              AND deleted_flag = FALSE
            LIMIT 1
            """)
    TenantMember findByTenantIdAndPid(@Param("tenantId") Long tenantId, @Param("pid") String pid);

    /**
     * Find a tenant member by internal ID within a tenant.
     */
    @Select("""
            SELECT *
            FROM ab_tenant_member
            WHERE tenant_id = #{tenantId}
              AND id = #{id}
              AND deleted_flag = FALSE
            LIMIT 1
            """)
    TenantMember findByTenantIdAndId(@Param("tenantId") Long tenantId, @Param("id") Long id);


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

    @Select("""
            SELECT COUNT(DISTINCT tm.id)
            FROM ab_tenant_member tm
            JOIN ab_user_role ur ON ur.member_id = tm.id
            WHERE tm.tenant_id = #{tenantId}
              AND tm.id <> #{excludedMemberId}
              AND LOWER(tm.status) = 'active'
              AND tm.deleted_flag = FALSE
              AND ur.role_id = #{roleId}
              AND LOWER(ur.status) = 'active'
              AND ur.deleted_flag = FALSE
            """)
    long countOtherActiveMembersWithRole(
            @Param("tenantId") Long tenantId,
            @Param("excludedMemberId") Long excludedMemberId,
            @Param("roleId") Long roleId);

    @Select("""
            SELECT tm.pid AS "memberPid",
                   COALESCE(NULLIF(u.nick_name, ''), NULLIF(u.user_name, ''), u.email) AS "displayName",
                   u.email
            FROM ab_tenant_member tm
            JOIN ab_user u ON u.id = tm.user_id
            WHERE tm.tenant_id = #{tenantId}
              AND tm.pid <> #{excludedMemberPid}
              AND LOWER(tm.status) = 'active'
              AND tm.deleted_flag = FALSE
              AND u.deleted_flag = FALSE
            ORDER BY "displayName", tm.pid
            """)
    List<java.util.Map<String, Object>> findActiveOffboardingCandidates(
            @Param("tenantId") Long tenantId,
            @Param("excludedMemberPid") String excludedMemberPid);

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
