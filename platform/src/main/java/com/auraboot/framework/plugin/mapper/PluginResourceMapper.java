package com.auraboot.framework.plugin.mapper;

import com.auraboot.framework.plugin.entity.PluginResource;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Mapper for plugin resource tracking.
 */
@Mapper
public interface PluginResourceMapper extends BaseMapper<PluginResource> {

    /**
     * Find resources by plugin PID.
     */
    @Select("SELECT * FROM ab_plugin_resource WHERE plugin_pid = #{pluginPid} ORDER BY sequence")
    List<PluginResource> findByPluginPid(@Param("pluginPid") String pluginPid);

    /**
     * Find resources by import ID.
     */
    @Select("SELECT * FROM ab_plugin_resource WHERE import_id = #{importId} ORDER BY sequence")
    List<PluginResource> findByImportId(@Param("importId") String importId);

    /**
     * Find resources by type for a plugin.
     */
    @Select("SELECT * FROM ab_plugin_resource WHERE plugin_pid = #{pluginPid} AND resource_type = #{resourceType} ORDER BY sequence")
    List<PluginResource> findByPluginPidAndType(@Param("pluginPid") String pluginPid, @Param("resourceType") String resourceType);

    /**
     * Find resource by type and code.
     */
    @Select("SELECT * FROM ab_plugin_resource WHERE tenant_id = #{tenantId} AND resource_type = #{resourceType} AND resource_code = #{resourceCode}")
    PluginResource findByTypeAndCode(@Param("tenantId") Long tenantId, @Param("resourceType") String resourceType, @Param("resourceCode") String resourceCode);

    /**
     * Check if a resource exists for a plugin.
     */
    @Select("SELECT COUNT(*) > 0 FROM ab_plugin_resource WHERE plugin_pid = #{pluginPid} AND resource_type = #{resourceType} AND resource_code = #{resourceCode}")
    boolean existsByPluginAndResource(@Param("pluginPid") String pluginPid, @Param("resourceType") String resourceType, @Param("resourceCode") String resourceCode);

    /**
     * Count resources by plugin.
     */
    @Select("SELECT COUNT(*) FROM ab_plugin_resource WHERE plugin_pid = #{pluginPid}")
    int countByPluginPid(@Param("pluginPid") String pluginPid);

    /**
     * Count resources by type for a plugin.
     */
    @Select("SELECT COUNT(*) FROM ab_plugin_resource WHERE plugin_pid = #{pluginPid} AND resource_type = #{resourceType}")
    int countByPluginPidAndType(@Param("pluginPid") String pluginPid, @Param("resourceType") String resourceType);

    /**
     * Delete resources by plugin PID.
     */
    @Delete("DELETE FROM ab_plugin_resource WHERE plugin_pid = #{pluginPid}")
    int deleteByPluginPid(@Param("pluginPid") String pluginPid);

    /**
     * Delete resources by import ID.
     */
    @Delete("DELETE FROM ab_plugin_resource WHERE import_id = #{importId}")
    int deleteByImportId(@Param("importId") String importId);

    /**
     * Find resources in reverse order for rollback.
     */
    @Select("SELECT * FROM ab_plugin_resource WHERE plugin_pid = #{pluginPid} AND action = 'create' ORDER BY sequence DESC")
    List<PluginResource> findCreatedResourcesForRollback(@Param("pluginPid") String pluginPid);

    /**
     * Find resources with previous state for rollback.
     */
    @Select("SELECT * FROM ab_plugin_resource WHERE plugin_pid = #{pluginPid} AND action = 'update' AND previous_state IS NOT NULL ORDER BY sequence DESC")
    List<PluginResource> findUpdatedResourcesForRollback(@Param("pluginPid") String pluginPid);

    /**
     * Find existing resource by tenant, plugin, type and code.
     */
    @Select("SELECT * FROM ab_plugin_resource WHERE tenant_id = #{tenantId} AND plugin_pid = #{pluginPid} AND resource_type = #{resourceType} AND resource_code = #{resourceCode}")
    PluginResource findByTenantPluginAndResource(@Param("tenantId") Long tenantId, @Param("pluginPid") String pluginPid,
                                                  @Param("resourceType") String resourceType, @Param("resourceCode") String resourceCode);

    /**
     * Delete a plugin resource tracking record by plugin PID and resource code.
     */
    @Delete("DELETE FROM ab_plugin_resource WHERE plugin_pid = #{pluginPid} AND resource_code = #{resourceCode}")
    int deleteByPluginPidAndCode(@Param("pluginPid") String pluginPid, @Param("resourceCode") String resourceCode);

    /**
     * Find user-modified resources for a plugin.
     */
    @Select("SELECT * FROM ab_plugin_resource WHERE plugin_pid = #{pluginPid} AND user_modified = TRUE ORDER BY sequence")
    List<PluginResource> findModifiedByPluginPid(@Param("pluginPid") String pluginPid);

    /**
     * Find user-claimed resources for a plugin.
     */
    @Select("SELECT * FROM ab_plugin_resource WHERE plugin_pid = #{pluginPid} AND ownership_type = 'user_claimed' ORDER BY sequence")
    List<PluginResource> findUserClaimedByPluginPid(@Param("pluginPid") String pluginPid);
}
