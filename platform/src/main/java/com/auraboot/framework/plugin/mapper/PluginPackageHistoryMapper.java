package com.auraboot.framework.plugin.mapper;

import com.auraboot.framework.plugin.entity.PluginPackageHistory;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * Mapper for plugin package installation history.
 */
@Mapper
public interface PluginPackageHistoryMapper extends BaseMapper<PluginPackageHistory> {

    /**
     * Find history by PID.
     */
    @Select("SELECT * FROM ab_plugin_package_history WHERE pid = #{pid}")
    PluginPackageHistory findByPid(@Param("pid") String pid);

    /**
     * Find history by plugin PID.
     */
    @Select("SELECT * FROM ab_plugin_package_history WHERE plugin_pid = #{pluginPid} ORDER BY created_at DESC")
    List<PluginPackageHistory> findByPluginPid(@Param("pluginPid") String pluginPid);

    /**
     * Find latest history for a plugin.
     */
    @Select("SELECT * FROM ab_plugin_package_history WHERE tenant_id = #{tenantId} AND plugin_id = #{pluginId} ORDER BY created_at DESC LIMIT 1")
    PluginPackageHistory findLatestByTenantAndPluginId(@Param("tenantId") Long tenantId, @Param("pluginId") String pluginId);

    /**
     * Find recent history for tenant.
     */
    @Select("SELECT * FROM ab_plugin_package_history WHERE tenant_id = #{tenantId} ORDER BY created_at DESC LIMIT #{limit}")
    List<PluginPackageHistory> findRecentByTenant(@Param("tenantId") Long tenantId, @Param("limit") int limit);

    /**
     * Find history by status.
     */
    @Select("SELECT * FROM ab_plugin_package_history WHERE tenant_id = #{tenantId} AND status = #{status} ORDER BY created_at DESC")
    List<PluginPackageHistory> findByTenantAndStatus(@Param("tenantId") Long tenantId, @Param("status") String status);

    /**
     * Find in-progress installations.
     */
    @Select("SELECT * FROM ab_plugin_package_history WHERE tenant_id = #{tenantId} AND status IN ('pending', 'parsing', 'installing_config', 'installing_backend', 'installing_frontend', 'rolling_back') ORDER BY created_at DESC")
    List<PluginPackageHistory> findInProgress(@Param("tenantId") Long tenantId);

    /**
     * Update overall status.
     */
    @Update("UPDATE ab_plugin_package_history SET status = #{status}, updated_at = NOW() WHERE pid = #{pid}")
    int updateStatus(@Param("pid") String pid, @Param("status") String status);

    /**
     * Update config component status.
     */
    @Update("UPDATE ab_plugin_package_history SET config_status = #{status}, config_error = #{error}, updated_at = NOW() WHERE pid = #{pid}")
    int updateConfigStatus(@Param("pid") String pid, @Param("status") String status, @Param("error") String error);

    /**
     * Update backend component status.
     */
    @Update("UPDATE ab_plugin_package_history SET backend_status = #{status}, backend_error = #{error}, updated_at = NOW() WHERE pid = #{pid}")
    int updateBackendStatus(@Param("pid") String pid, @Param("status") String status, @Param("error") String error);

    /**
     * Update frontend component status.
     */
    @Update("UPDATE ab_plugin_package_history SET frontend_status = #{status}, frontend_error = #{error}, updated_at = NOW() WHERE pid = #{pid}")
    int updateFrontendStatus(@Param("pid") String pid, @Param("status") String status, @Param("error") String error);

    /**
     * Mark installation as completed successfully.
     */
    @Update("UPDATE ab_plugin_package_history SET status = 'success', plugin_pid = #{pluginPid}, can_rollback = true, completed_at = NOW(), updated_at = NOW() WHERE pid = #{pid}")
    int markSuccess(@Param("pid") String pid, @Param("pluginPid") String pluginPid);

    /**
     * Mark installation as failed.
     */
    @Update("UPDATE ab_plugin_package_history SET status = 'failed', error_message = #{errorMessage}, completed_at = NOW(), updated_at = NOW() WHERE pid = #{pid}")
    int markFailed(@Param("pid") String pid, @Param("errorMessage") String errorMessage);

    /**
     * Mark as rolled back.
     */
    @Update("UPDATE ab_plugin_package_history SET status = 'rolled_back', completed_at = NOW(), updated_at = NOW() WHERE pid = #{pid}")
    int markRolledBack(@Param("pid") String pid);
}
