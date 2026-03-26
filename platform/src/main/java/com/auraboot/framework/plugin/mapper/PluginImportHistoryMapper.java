package com.auraboot.framework.plugin.mapper;

import com.auraboot.framework.plugin.entity.PluginImportHistory;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * Mapper for plugin import history.
 */
@Mapper
public interface PluginImportHistoryMapper extends BaseMapper<PluginImportHistory> {

    /**
     * Find import history by import ID.
     */
    @Select("SELECT * FROM ab_plugin_import_history WHERE import_id = #{importId}")
    PluginImportHistory findByImportId(@Param("importId") String importId);

    /**
     * Find import history by tenant and plugin ID.
     */
    @Select("SELECT * FROM ab_plugin_import_history WHERE tenant_id = #{tenantId} AND plugin_id = #{pluginId} ORDER BY created_at DESC")
    List<PluginImportHistory> findByTenantAndPluginId(@Param("tenantId") Long tenantId, @Param("pluginId") String pluginId);

    /**
     * Find latest import for a plugin.
     */
    @Select("SELECT * FROM ab_plugin_import_history WHERE tenant_id = #{tenantId} AND plugin_id = #{pluginId} ORDER BY created_at DESC LIMIT 1")
    PluginImportHistory findLatestByTenantAndPluginId(@Param("tenantId") Long tenantId, @Param("pluginId") String pluginId);

    /**
     * Find imports by status.
     */
    @Select("SELECT * FROM ab_plugin_import_history WHERE tenant_id = #{tenantId} AND status = #{status} ORDER BY created_at DESC")
    List<PluginImportHistory> findByTenantAndStatus(@Param("tenantId") Long tenantId, @Param("status") String status);

    /**
     * Find successful imports for a plugin.
     */
    @Select("SELECT * FROM ab_plugin_import_history WHERE tenant_id = #{tenantId} AND plugin_id = #{pluginId} AND status = 'success' ORDER BY created_at DESC LIMIT 1")
    PluginImportHistory findLastSuccessful(@Param("tenantId") Long tenantId, @Param("pluginId") String pluginId);

    /**
     * Update import status.
     */
    @Update("UPDATE ab_plugin_import_history SET status = #{status}, updated_at = NOW() WHERE import_id = #{importId}")
    int updateStatus(@Param("importId") String importId, @Param("status") String status);

    /**
     * Mark import as completed with result.
     */
    @Update("UPDATE ab_plugin_import_history SET status = #{status}, completed_at = NOW(), updated_at = NOW() WHERE import_id = #{importId}")
    int markCompleted(@Param("importId") String importId, @Param("status") String status);

    /**
     * Mark import as failed with error message.
     */
    @Update("UPDATE ab_plugin_import_history SET status = 'failed', error_message = #{errorMessage}, error_detail = #{errorDetail}, completed_at = NOW(), updated_at = NOW() WHERE import_id = #{importId}")
    int markFailed(@Param("importId") String importId, @Param("errorMessage") String errorMessage, @Param("errorDetail") String errorDetail);
}
