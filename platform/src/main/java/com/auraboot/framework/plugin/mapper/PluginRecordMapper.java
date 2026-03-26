package com.auraboot.framework.plugin.mapper;

import com.auraboot.framework.plugin.entity.PluginRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * Plugin record MyBatis mapper.
 * Table: ab_plugin
 */
@Mapper
public interface PluginRecordMapper extends BaseMapper<PluginRecord> {

    /**
     * Find plugin by PID.
     *
     * @param pid business unique identifier
     * @return plugin record or null
     */
    @Select("SELECT * FROM ab_plugin WHERE pid = #{pid} AND deleted_flag = false")
    PluginRecord findByPid(@Param("pid") String pid);

    /**
     * Find plugin by namespace within a tenant.
     *
     * @param tenantId tenant ID
     * @param namespace plugin namespace
     * @return plugin record or null
     */
    @Select("SELECT * FROM ab_plugin WHERE namespace = #{namespace} AND deleted_flag = false")
    PluginRecord findByTenantAndNamespace(@Param("namespace") String namespace);

    /**
     * Find plugin by plugin ID within a tenant.
     *
     * @param tenantId tenant ID
     * @param pluginId plugin identifier
     * @return plugin record or null
     */
    @Select("SELECT * FROM ab_plugin WHERE plugin_id = #{pluginId} AND deleted_flag = false")
    PluginRecord findByTenantAndPluginId(@Param("pluginId") String pluginId);

    /**
     * Find all plugins for a tenant.
     *
     * @param tenantId tenant ID
     * @return list of plugin records
     */
    @Select("SELECT * FROM ab_plugin WHERE deleted_flag = false ORDER BY created_at DESC")
    List<PluginRecord> findByTenant();

    /**
     * Find plugins by status within a tenant.
     *
     * @param tenantId tenant ID
     * @param status plugin status
     * @return list of plugin records
     */
    @Select("SELECT * FROM ab_plugin WHERE status = #{status} AND deleted_flag = false ORDER BY created_at DESC")
    List<PluginRecord> findByTenantAndStatus(@Param("status") String status);

    /**
     * Find all enabled plugins for a tenant.
     *
     * @param tenantId tenant ID
     * @return list of enabled plugin records
     */
    @Select("SELECT * FROM ab_plugin WHERE status = 'enabled' AND deleted_flag = false ORDER BY created_at DESC")
    List<PluginRecord> findEnabledByTenant();

    /**
     * Find plugin by namespace within a tenant, including soft-deleted records.
     * Used to handle reinstallation after uninstall (unique constraint includes soft-deleted rows).
     *
     * @param tenantId tenant ID
     * @param namespace plugin namespace
     * @return plugin record or null
     */
    @Select("SELECT * FROM ab_plugin WHERE namespace = #{namespace} LIMIT 1")
    PluginRecord findByTenantAndNamespaceIncludeDeleted(@Param("namespace") String namespace);

    /**
     * Check if a namespace is available (not used by another plugin).
     *
     * @param tenantId tenant ID
     * @param namespace namespace to check
     * @return true if namespace is available
     */
    @Select("SELECT COUNT(*) = 0 FROM ab_plugin WHERE namespace = #{namespace} AND deleted_flag = false")
    boolean isNamespaceAvailable(@Param("namespace") String namespace);

    /**
     * Update plugin status.
     *
     * @param pid plugin PID
     * @param status new status
     * @return number of rows updated
     */
    @Update("UPDATE ab_plugin SET status = #{status}, updated_at = NOW() WHERE pid = #{pid} AND deleted_flag = false")
    int updateStatus(@Param("pid") String pid, @Param("status") String status);

    /**
     * Update plugin status with enabled_at timestamp.
     *
     * @param pid plugin PID
     * @return number of rows updated
     */
    @Update("UPDATE ab_plugin SET status = 'enabled', enabled_at = NOW(), updated_at = NOW() WHERE pid = #{pid} AND deleted_flag = false")
    int markAsEnabled(@Param("pid") String pid);

    /**
     * Update plugin status with disabled_at timestamp.
     *
     * @param pid plugin PID
     * @return number of rows updated
     */
    @Update("UPDATE ab_plugin SET status = 'disabled', disabled_at = NOW(), updated_at = NOW() WHERE pid = #{pid} AND deleted_flag = false")
    int markAsDisabled(@Param("pid") String pid);

    /**
     * Soft delete a plugin.
     *
     * @param pid plugin PID
     * @return number of rows updated
     */
    @Update("UPDATE ab_plugin SET deleted_flag = TRUE, updated_at = NOW() WHERE pid = #{pid}")
    int softDelete(@Param("pid") String pid);

    /**
     * Resurrect a soft-deleted plugin record, updating all fields for reinstallation.
     * Bypasses @TableLogic filter to update records with deleted_flag = TRUE.
     */
    @Update("UPDATE ab_plugin SET plugin_id = #{pluginId}, namespace = #{namespace}, version = #{version}, " +
            "display_name = #{displayName}, status = #{status}, " +
            "has_config = #{hasConfig}, has_backend = #{hasBackend}, has_frontend = #{hasFrontend}, " +
            "deleted_flag = false, installed_at = NOW(), updated_at = NOW() " +
            "WHERE pid = #{pid}")
    int resurrectPlugin(@Param("pid") String pid, @Param("pluginId") String pluginId,
                        @Param("namespace") String namespace, @Param("version") String version,
                        @Param("displayName") String displayName, @Param("status") String status,
                        @Param("hasConfig") boolean hasConfig, @Param("hasBackend") boolean hasBackend,
                        @Param("hasFrontend") boolean hasFrontend);

    /**
     * Count plugins by status for a tenant.
     *
     * @param tenantId tenant ID
     * @param status plugin status
     * @return count
     */
    @Select("SELECT COUNT(*) FROM ab_plugin WHERE status = #{status} AND deleted_flag = false")
    int countByTenantAndStatus(@Param("status") String status);
}
