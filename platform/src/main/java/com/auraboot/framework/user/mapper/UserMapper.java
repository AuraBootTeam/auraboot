package com.auraboot.framework.user.mapper;

import com.auraboot.framework.user.dao.entity.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

public interface UserMapper extends BaseMapper<User> {

    /**
     * Explicitly clear the reset password token and sent-at timestamp for a user.
     * Required because updateById skips null fields by default.
     */
    @Update("UPDATE ab_user SET reset_password_token = NULL, reset_password_sent_at = NULL WHERE id = #{userId}")
    void clearResetToken(@Param("userId") Long userId);

    /**
     * Get all distinct tenant IDs that have active human users (excludes SYSTEM_AGENT accounts).
     */
    @Select("SELECT DISTINCT tenant_id FROM ab_user WHERE tenant_id IS NOT NULL AND deleted_flag = FALSE " +
            "AND (user_type IS NULL OR user_type = 'human')")
    List<Long> getDistinctTenantIds();

    /**
     * Get the first human user ID for a given tenant (typically admin).
     * Excludes SYSTEM_AGENT and SERVICE_ACCOUNT users.
     */
    @Select("SELECT id FROM ab_user WHERE tenant_id = #{tenantId} AND deleted_flag = FALSE " +
            "AND (user_type IS NULL OR user_type = 'human') ORDER BY id ASC LIMIT 1")
    Long getFirstUserIdByTenant(@Param("tenantId") Long tenantId);

    /**
     * Look up a SYSTEM_AGENT user by email convention.
     * Used to retrieve the agent's identity user ID for created_by/updated_by attribution.
     */
    @Select("SELECT id FROM ab_user WHERE email = #{email} AND user_type = 'system_agent' " +
            "AND deleted_flag = FALSE LIMIT 1")
    Long findSystemAgentUserIdByEmail(@Param("email") String email);

    /**
     * Search users within a tenant by display name or email.
     * Joins ab_user → ab_tenant_member (tenant filter) and optionally
     * mt_org_employee → mt_org_department (department name).
     * Excludes the requesting user and non-human accounts.
     */
    @Select("""
            SELECT u.id AS id,
                   u.pid AS pid,
                   COALESCE(u.nick_name, u.user_name, u.email) AS display_name,
                   u.email AS email,
                   u.img_id AS avatar_url,
                   d.org_dept_name AS department_name
            FROM ab_user u
            INNER JOIN ab_tenant_member tm
                    ON tm.user_id = u.id
                   AND tm.tenant_id = #{tenantId}
                   AND tm.status = 'active'
                   AND tm.deleted_flag = FALSE
            LEFT JOIN mt_org_employee e
                   ON e.org_emp_user_id = CAST(u.id AS VARCHAR)
                  AND e.tenant_id = #{tenantId}
            LEFT JOIN mt_org_department d
                   ON d.id = CAST(e.org_emp_dept_id AS BIGINT)
                  AND d.tenant_id = #{tenantId}
            WHERE u.deleted_flag = FALSE
              AND (u.user_type IS NULL OR u.user_type = 'human')
              AND u.id != COALESCE(#{excludeUserId,jdbcType=BIGINT}, -1)
              AND (
                  LOWER(COALESCE(u.nick_name, '')) LIKE LOWER(#{keyword})
                  OR LOWER(COALESCE(u.email, '')) LIKE LOWER(#{keyword})
                  OR LOWER(COALESCE(u.user_name, '')) LIKE LOWER(#{keyword})
              )
            ORDER BY u.nick_name ASC NULLS LAST
            LIMIT #{limit}
            """)
    List<Map<String, Object>> searchUsersByTenant(
            @Param("tenantId") Long tenantId,
            @Param("excludeUserId") Long excludeUserId,
            @Param("keyword") String keyword,
            @Param("limit") int limit);

    /**
     * Single-user lookup with the same tenant-scoped projection as
     * {@link #searchUsersByTenant}, used by picker resolve-name flows.
     * Returns null when the user does not exist or is not a member of the tenant.
     */
    @Select("""
            SELECT u.pid AS pid,
                   COALESCE(u.nick_name, u.user_name, u.email) AS display_name,
                   u.email AS email,
                   u.img_id AS avatar_url,
                   d.org_dept_name AS department_name
            FROM ab_user u
            INNER JOIN ab_tenant_member tm
                    ON tm.user_id = u.id
                   AND tm.tenant_id = #{tenantId}
                   AND tm.status = 'active'
                   AND tm.deleted_flag = FALSE
            LEFT JOIN mt_org_employee e
                   ON e.org_emp_user_id = CAST(u.id AS VARCHAR)
                  AND e.tenant_id = #{tenantId}
            LEFT JOIN mt_org_department d
                   ON d.id = CAST(e.org_emp_dept_id AS BIGINT)
                  AND d.tenant_id = #{tenantId}
            WHERE u.deleted_flag = FALSE
              AND u.pid = #{pid}
            LIMIT 1
            """)
    Map<String, Object> findUserInTenantByPid(
            @Param("tenantId") Long tenantId,
            @Param("pid") String pid);
}
