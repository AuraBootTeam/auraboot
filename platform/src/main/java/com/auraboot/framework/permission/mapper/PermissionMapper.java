package com.auraboot.framework.permission.mapper;

import com.auraboot.framework.permission.entity.Permission;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.List;

/**
 * Permission Mapper Interface (V4)
 *
 * Provides data access operations for Permission entity.
 *
 * Key Features:
 * - Git-first lifecycle management
 * - Tenant isolation (auto-injected by MetaContextMyBatisInterceptor)
 * - Soft delete support
 * - Release tracking
 *
 * @author AuraBoot Platform
 * @version 4.0.0
 * @since 2025-01-07
 */
@Mapper
public interface PermissionMapper extends BaseMapper<Permission> {

    /**
     * Find permission by code
     *
     * @param code Permission code (e.g., "model.user_model.create")
     * @return Permission entity or null if not found
     */
    @Select("""
        SELECT * FROM ab_permission
        WHERE LOWER(code) = LOWER(#{code})
          AND deleted_flag = false
        ORDER BY created_at DESC
        LIMIT 1
        """)
    Permission findByCode(@Param("code") String code);

    /**
     * Find permissions by resource type
     *
     * @param resourceType Resource type (MODEL, PAGE, QUERY, etc.)
     * @return List of permissions
     */
    @Select("""
        SELECT * FROM ab_permission
        WHERE resource_type = #{resourceType}
          AND deleted_flag = false
        ORDER BY resource_code, action
        """)
    List<Permission> findByResourceType(@Param("resourceType") String resourceType);

    /**
     * Find permissions by resource code
     *
     * @param resourceType Resource type
     * @param resourceCode Resource code
     * @return List of permissions
     */
    @Select("""
        SELECT * FROM ab_permission
        WHERE resource_type = #{resourceType}
          AND resource_code = #{resourceCode}
          AND deleted_flag = false
        ORDER BY action
        """)
    List<Permission> findByResource(
        @Param("resourceType") String resourceType,
        @Param("resourceCode") String resourceCode
    );

    /**
     * Find permissions by status
     *
     * @param status Status (ACTIVE, DEPRECATED, ARCHIVED)
     * @return List of permissions
     */
    @Select("""
        SELECT * FROM ab_permission
        WHERE status = #{status}
          AND deleted_flag = false
        ORDER BY created_at DESC
        """)
    List<Permission> findByStatus(@Param("status") String status);

    /**
     * Find deprecated permissions that should be archived
     *
     * @param thresholdDate Threshold date (e.g., 6 months ago)
     * @return List of permissions to be archived
     */
    @Select("""
        SELECT * FROM ab_permission
        WHERE status = 'deprecated'
          AND deprecated_at < #{thresholdDate}
          AND deleted_flag = false
        ORDER BY deprecated_at
        """)
    List<Permission> findDeprecatedForArchive(@Param("thresholdDate") java.time.Instant thresholdDate);

    /**
     * Find child permissions by parent ID
     *
     * @param parentId Parent permission ID
     * @return List of child permissions
     */
    @Select("""
        SELECT * FROM ab_permission
        WHERE parent_id = #{parentId}
          AND deleted_flag = false
        ORDER BY level, created_at
        """)
    List<Permission> findChildren(@Param("parentId") Long parentId);

    /**
     * Find permissions by IDs
     *
     * @param ids List of permission IDs
     * @return List of permissions
     */
    @Select("""
        <script>
        SELECT * FROM ab_permission
        WHERE id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">
          #{id}
        </foreach>
          AND deleted_flag = false
        ORDER BY created_at DESC
        </script>
        """)
    List<Permission> findByIds(@Param("ids") List<Long> ids);

    /**
     * Find permissions by tags
     *
     * @param tags Tags to search
     * @return List of permissions
     */
    @Select("""
        SELECT * FROM ab_permission
        WHERE tags && #{tags}::text[]
          AND deleted_flag = false
        ORDER BY created_at DESC
        """)
    List<Permission> findByTags(@Param("tags") String[] tags);

    /**
     * Check if permission code exists
     *
     * @param code Permission code
     * @param excludeId ID to exclude (for update operations)
     * @return Count (0 or 1)
     */
    @Select("""
        <script>
        SELECT COUNT(*)
        FROM ab_permission
        WHERE code = #{code}
          AND deleted_flag = false
        <if test="excludeId != null">
          AND id != #{excludeId}
        </if>
        </script>
        """)
    int countByCode(
        @Param("code") String code,
        @Param("excludeId") Long excludeId
    );

    /**
     * Update permission status
     *
     * @param id Permission ID
     * @param status New status
     * @param timestamp Timestamp for status change
     * @return Number of rows updated
     */
    @Update("""
        UPDATE ab_permission
        SET status = #{status},
            updated_at = now()
        <if test="status == 'deprecated'">
            , deprecated_at = #{timestamp}
        </if>
        <if test="status == 'archived'">
            , archived_at = #{timestamp}
        </if>
        WHERE id = #{id}
        """)
    int updateStatus(
        @Param("id") Long id,
        @Param("status") String status,
        @Param("timestamp") java.time.Instant timestamp
    );

    /**
     * Batch insert permissions
     *
     * @param permissions List of permissions to insert
     * @return Number of rows inserted
     */
    @Insert("""
        <script>
        INSERT INTO ab_permission (
            pid, tenant_id,
            code, name, description,
            resource_type, resource_code, action,
            source, source_ref,
            parent_id, path, level,
            data_scope_type, data_scope_config,
            extension, tags,
            status, deleted_flag,
            created_at, updated_at, created_by, updated_by
        ) VALUES
        <foreach collection="permissions" item="cap" separator=",">
        (
            #{cap.pid}, #{cap.tenantId},
            #{cap.code}, #{cap.name}, #{cap.description},
            #{cap.resourceType}, #{cap.resourceCode}, #{cap.action},
            #{cap.source}, #{cap.sourceRef},
            #{cap.parentId}, #{cap.path}, #{cap.level},
            #{cap.dataScopeType}, #{cap.dataScopeConfig},
            #{cap.extension},
            #{cap.tags, typeHandler=org.apache.ibatis.type.ArrayTypeHandler},
            #{cap.status}, #{cap.deletedFlag},
            #{cap.createdAt}, #{cap.updatedAt}, #{cap.createdBy}, #{cap.updatedBy}
        )
        </foreach>
        </script>
        """)
    int batchInsert(@Param("permissions") List<Permission> permissions);

    /**
     * Soft delete permission
     *
     * @param id Permission ID
     * @return Number of rows updated
     */
    @Update("""
        UPDATE ab_permission
        SET deleted_flag = TRUE,
            updated_at = now()
        WHERE id = #{id}
        """)
    int softDelete(@Param("id") Long id);
    
    /**
     * Find permissions by PIDs
     *
     * @param pids List of permission PIDs
     * @param tenantId Tenant ID
      
     * @return List of permissions
     */
    @Select("""
        <script>
        SELECT * FROM ab_permission
        WHERE pid IN
        <foreach collection="pids" item="pid" open="(" separator="," close=")">
          #{pid}
        </foreach>
          AND deleted_flag = false
        ORDER BY created_at DESC
        </script>
        """)
    List<Permission> findByPids(
        @Param("pids") List<String> pids
    );

    // ==================== Plugin Import Support ====================

    /**
     * Update permission fields for plugin import.
     */
    @Update("""
        UPDATE ab_permission SET
            name = #{name}, description = #{description}, category = #{category},
            resource_type = #{resourceType}, resource_code = #{resourceCode}, action = #{action},
            data_scope_type = #{dataScopeType}, data_scope_config = #{dataScopeConfig}::jsonb,
            extension = #{extension}::jsonb, tags = #{tags}::text[],
            plugin_pid = #{pluginPid}, updated_at = NOW()
        WHERE tenant_id = #{tenantId} AND code = #{code} AND deleted_flag = FALSE
        """)
    int updateForPluginImport(@Param("name") String name,
                              @Param("description") String description,
                              @Param("category") String category,
                              @Param("resourceType") String resourceType,
                              @Param("resourceCode") String resourceCode,
                              @Param("action") String action,
                              @Param("dataScopeType") String dataScopeType,
                              @Param("dataScopeConfig") String dataScopeConfig,
                              @Param("extension") String extension,
                              @Param("tags") String tags,
                              @Param("pluginPid") String pluginPid,
                              @Param("tenantId") Long tenantId,
                              @Param("code") String code);
}
