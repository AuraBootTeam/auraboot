package com.auraboot.framework.rbac.mapper;

import com.auraboot.framework.rbac.entity.RolePermission;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * RolePermission Mapper Interface (V4)
 *
 * Provides data access operations for Role-Permission bindings.
 *
 * Key Features:
 * - GRANT/DENY semantics
 * - Temporal control (effective_date, expiry_date)
 * - Priority-based conflict resolution
 * - Tenant isolation (auto-injected)
 *
 * @author AuraBoot Platform
 * @version 4.0.0
 * @since 2025-01-07
 */
@Mapper
public interface RolePermissionMapper extends BaseMapper<RolePermission> {

    /**
     * Find all permission bindings for a role
     *
     * @param roleId Role ID
     * @return List of role permission bindings
     */
    @Select("""
        SELECT * FROM ab_role_permission
        WHERE role_id = #{roleId}
          AND deleted_flag = false
        ORDER BY priority DESC, created_at DESC
        """)
    List<RolePermission> findByRole(@Param("roleId") Long roleId);

    /**
     * Find all role bindings for a permission
     *
     * @param permissionId Permission ID
     * @return List of role permission bindings
     */
    @Select("""
        SELECT * FROM ab_role_permission
        WHERE permission_id = #{permissionId}
          AND deleted_flag = false
        ORDER BY role_id, priority DESC
        """)
    List<RolePermission> findByPermission(@Param("permissionId") Long permissionId);

    /**
     * Find effective bindings for a role
     *
     * Filters by effective_date and expiry_date.
     *
     * @param roleId Role ID
     * @param currentDate Current date
     * @return List of effective role permission bindings
     */
    @Select("""
        SELECT * FROM ab_role_permission
        WHERE role_id = #{roleId}
          AND deleted_flag = false
          AND status = 'active'
          AND (effective_date IS NULL OR effective_date <= #{currentDate})
          AND (expiry_date IS NULL OR expiry_date >= #{currentDate})
        ORDER BY priority DESC, created_at DESC
        """)
    List<RolePermission> findEffectiveByRole(
        @Param("roleId") Long roleId,
        @Param("currentDate") LocalDate currentDate
    );

    /**
     * Find permission IDs for a role
     *
     * Returns only GRANT bindings (excludes DENY).
     *
     * @param roleId Role ID
     * @return Set of permission IDs
     */
    @Select("""
        SELECT DISTINCT permission_id
        FROM ab_role_permission
        WHERE role_id = #{roleId}
          AND grant_type = 'grant'
          AND deleted_flag = false
          AND status = 'active'
          AND (effective_date IS NULL OR effective_date <= CURRENT_DATE)
          AND (expiry_date IS NULL OR expiry_date >= CURRENT_DATE)
        """)
    Set<Long> findPermissionIdsByRole(@Param("roleId") Long roleId);

    /**
     * Find permission IDs for multiple roles
     *
     * Used for user permission resolution.
     *
     * @param roleIds List of role IDs
     * @return Set of permission IDs
     */
    @Select("""
        <script>
        SELECT DISTINCT permission_id
        FROM ab_role_permission
        WHERE role_id IN
        <foreach collection="roleIds" item="id" open="(" separator="," close=")">
          #{id}
        </foreach>
          AND grant_type = 'grant'
          AND deleted_flag = false
          AND status = 'active'
          AND (effective_date IS NULL OR effective_date &lt;= CURRENT_DATE)
          AND (expiry_date IS NULL OR expiry_date &gt;= CURRENT_DATE)
        </script>
        """)
    Set<Long> findPermissionIdsByRoles(@Param("roleIds") List<Long> roleIds);

    /**
     * Check if role has permission (considering GRANT/DENY)
     *
     * @param roleId Role ID
     * @param permissionId Permission ID
     * @return true if granted, false if denied or not found
     */
    @Select("""
        SELECT
            CASE
                WHEN COUNT(CASE WHEN grant_type = 'deny' THEN 1 END) > 0 THEN FALSE
                WHEN COUNT(CASE WHEN grant_type = 'grant' THEN 1 END) > 0 THEN TRUE
                ELSE FALSE
            END
        FROM ab_role_permission
        WHERE role_id = #{roleId}
          AND permission_id = #{permissionId}
          AND deleted_flag = false
          AND status = 'active'
          AND (effective_date IS NULL OR effective_date <= CURRENT_DATE)
          AND (expiry_date IS NULL OR expiry_date >= CURRENT_DATE)
        """)
    boolean hasPermission(
        @Param("roleId") Long roleId,
        @Param("permissionId") Long permissionId
    );

    /**
     * Find DENY bindings for a role
     *
     * @param roleId Role ID
     * @return List of DENY bindings
     */
    @Select("""
        SELECT * FROM ab_role_permission
        WHERE role_id = #{roleId}
          AND grant_type = 'deny'
          AND deleted_flag = false
          AND status = 'active'
        ORDER BY priority DESC
        """)
    List<RolePermission> findDenyBindings(@Param("roleId") Long roleId);

    /**
     * Find expired bindings
     *
     * @param currentDate Current date
     * @return List of expired bindings
     */
    @Select("""
        SELECT * FROM ab_role_permission
        WHERE expiry_date < #{currentDate}
          AND deleted_flag = false
          AND status = 'active'
        ORDER BY expiry_date
        """)
    List<RolePermission> findExpiredBindings(@Param("currentDate") LocalDate currentDate);

    /**
     * Find binding by role and permission
     *
     * @param roleId Role ID
     * @param permissionId Permission ID
     * @return Role permission binding or null if not found
     */
    @Select("""
        SELECT * FROM ab_role_permission
        WHERE role_id = #{roleId}
          AND permission_id = #{permissionId}
          AND deleted_flag = false
        LIMIT 1
        """)
    RolePermission findByRoleAndPermission(
        @Param("roleId") Long roleId,
        @Param("permissionId") Long permissionId
    );

    /**
     * Check if binding exists
     *
     * @param roleId Role ID
     * @param permissionId Permission ID
     * @param excludeId ID to exclude (for update operations)
     * @return Count (0 or 1)
     */
    @Select("""
        <script>
        SELECT COUNT(*)
        FROM ab_role_permission
        WHERE role_id = #{roleId}
          AND permission_id = #{permissionId}
          AND deleted_flag = false
        <if test="excludeId != null">
          AND id != #{excludeId}
        </if>
        </script>
        """)
    int countByBinding(
        @Param("roleId") Long roleId,
        @Param("permissionId") Long permissionId,
        @Param("excludeId") Long excludeId
    );

    /**
     * Batch insert role permission bindings
     *
     * @param bindings List of bindings to insert
     * @return Number of rows inserted
     */
    @Insert("""
        <script>
        INSERT INTO ab_role_permission (
            pid, tenant_id,  
            role_id, permission_id,
            grant_type, priority,
            effective_date, expiry_date,
            conditions, status, deleted_flag,
            created_at, updated_at, created_by, updated_by
        ) VALUES
        <foreach collection="bindings" item="binding" separator=",">
        (
            #{binding.pid}, #{binding.tenantId},
            #{binding.roleId}, #{binding.permissionId},
            #{binding.grantType}, #{binding.priority},
            #{binding.effectiveDate}, #{binding.expiryDate},
            #{binding.conditions},
            #{binding.status}, #{binding.deletedFlag},
            #{binding.createdAt}, #{binding.updatedAt}, #{binding.createdBy}, #{binding.updatedBy}
        )
        </foreach>
        ON CONFLICT (tenant_id, role_id, permission_id)
        DO UPDATE SET
            grant_type = EXCLUDED.grant_type,
            priority = EXCLUDED.priority,
            effective_date = EXCLUDED.effective_date,
            expiry_date = EXCLUDED.expiry_date,
            conditions = EXCLUDED.conditions,
            status = EXCLUDED.status,
            deleted_flag = FALSE,
            updated_at = now(),
            updated_by = EXCLUDED.updated_by
        </script>
        """)
    int batchInsert(@Param("bindings") List<RolePermission> bindings);

    /**
     * Update binding status
     *
     * @param id Binding ID
     * @param status New status
     * @return Number of rows updated
     */
    @Update("""
        UPDATE ab_role_permission
        SET status = #{status},
            updated_at = now()
        WHERE id = #{id}
        """)
    int updateStatus(
        @Param("id") Long id,
        @Param("status") String status
    );

    /**
     * Delete bindings by role
     *
     * @param roleId Role ID
     * @return Number of rows deleted
     */
    @Update("""
        UPDATE ab_role_permission
        SET deleted_flag = TRUE,
            updated_at = now()
        WHERE role_id = #{roleId}
        """)
    int deleteByRole(@Param("roleId") Long roleId);

    /**
     * Delete bindings by permission
     *
     * @param permissionId Permission ID
     * @return Number of rows deleted
     */
    @Update("""
        UPDATE ab_role_permission
        SET deleted_flag = TRUE,
            updated_at = now()
        WHERE permission_id = #{permissionId}
        """)
    int deleteByPermission(@Param("permissionId") Long permissionId);

    /**
     * Soft delete binding
     *
     * @param id Binding ID
     * @return Number of rows updated
     */
    @Update("""
        UPDATE ab_role_permission
        SET deleted_flag = TRUE,
            updated_at = now()
        WHERE id = #{id}
        """)
    int softDelete(@Param("id") Long id);

    /**
     * Get binding statistics by grant type
     *
     * @return List of statistics (grant_type, count)
     */
    @Select("""
        SELECT
            grant_type,
            COUNT(*) as count
        FROM ab_role_permission
        WHERE deleted_flag = false
          AND status = 'active'
        GROUP BY grant_type
        ORDER BY grant_type
        """)
    @Results({
        @Result(property = "grantType", column = "grant_type"),
        @Result(property = "count", column = "count")
    })
    List<BindingStatistics> getStatisticsByGrantType();

    /**
     * Binding statistics DTO
     */
    class BindingStatistics {
        private String grantType;
        private Long count;

        // Getters and setters
        public String getGrantType() { return grantType; }
        public void setGrantType(String grantType) { this.grantType = grantType; }

        public Long getCount() { return count; }
        public void setCount(Long count) { this.count = count; }
    }
    
    /**
     * Find bindings by role ID with tenant context
     *
     * @param roleId Role ID
     * @param tenantId Tenant ID
      
     * @return List of role permission bindings
     */
    @Select("""
        SELECT * FROM ab_role_permission
        WHERE role_id = #{roleId}
          AND tenant_id = #{tenantId}
          AND deleted_flag = false
        ORDER BY priority DESC, created_at DESC
        """)
    List<RolePermission> findByRoleId(
        @Param("roleId") Long roleId,
        @Param("tenantId") Long tenantId
             
         
    );
    
    /**
     * Count bindings by role and permission with tenant context
     *
     * @param roleId Role ID
     * @param permissionId Permission ID
     * @param tenantId Tenant ID
      
     * @return Count
     */
    @Select("""
        SELECT COUNT(*)
        FROM ab_role_permission
        WHERE role_id = #{roleId}
          AND permission_id = #{permissionId}
          AND tenant_id = #{tenantId}
          AND deleted_flag = false
        """)
    int countByRoleAndPermission(
        @Param("roleId") Long roleId,
        @Param("permissionId") Long permissionId,
        @Param("tenantId") Long tenantId
             
         
    );
    
    /**
     * Delete binding by role and permission with tenant context
     *
     * @param roleId Role ID
     * @param permissionId Permission ID
     * @param tenantId Tenant ID
      
     * @return Number of rows deleted
     */
    @Update("""
        UPDATE ab_role_permission
        SET deleted_flag = TRUE,
            updated_at = now()
        WHERE role_id = #{roleId}
          AND permission_id = #{permissionId}
          AND tenant_id = #{tenantId}
            
          
        """)
    int deleteByRoleAndPermission(
        @Param("roleId") Long roleId,
        @Param("permissionId") Long permissionId,
        @Param("tenantId") Long tenantId
             
         
    );
    
    /**
     * Delete all bindings by role ID with tenant context
     *
     * @param roleId Role ID
     * @param tenantId Tenant ID
      
     * @return Number of rows deleted
     */
    @Update("""
        UPDATE ab_role_permission
        SET deleted_flag = TRUE,
            updated_at = now()
        WHERE role_id = #{roleId}
          AND tenant_id = #{tenantId}
            
          
        """)
    int deleteByRoleId(
        @Param("roleId") Long roleId,
        @Param("tenantId") Long tenantId


    );

    /**
     * Get conditions JSONB as raw string — bypasses type handler issues.
     */
    @Select("SELECT conditions::text FROM ab_role_permission WHERE id = #{id} AND deleted_flag = false")
    String getConditionsById(@Param("id") Long id);

    /**
     * Update conditions JSONB directly — bypasses type handler serialization issues.
     */
    @Update("UPDATE ab_role_permission SET conditions = #{json}::jsonb, updated_at = NOW() WHERE id = #{id}")
    void updateConditionsById(@Param("id") Long id, @Param("json") String json);
}
