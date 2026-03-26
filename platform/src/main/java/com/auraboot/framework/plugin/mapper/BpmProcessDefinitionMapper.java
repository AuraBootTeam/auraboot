package com.auraboot.framework.plugin.mapper;

import com.auraboot.framework.plugin.entity.BpmProcessDefinition;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * Mapper for BPM process definitions.
 */
@Mapper
public interface BpmProcessDefinitionMapper extends BaseMapper<BpmProcessDefinition> {

    /**
     * Find by PID.
     */
    @Select("SELECT * FROM ab_bpm_process_definition WHERE pid = #{pid} AND deleted_flag = false")
    BpmProcessDefinition findByPid(@Param("pid") String pid);

    /**
     * Find by process key.
     */
    @Select("SELECT * FROM ab_bpm_process_definition WHERE tenant_id = #{tenantId} AND process_key = #{processKey} AND is_current = TRUE AND deleted_flag = false")
    BpmProcessDefinition findByProcessKey(@Param("tenantId") Long tenantId, @Param("processKey") String processKey);

    /**
     * Find all versions of a process.
     */
    @Select("SELECT * FROM ab_bpm_process_definition WHERE tenant_id = #{tenantId} AND process_key = #{processKey} AND deleted_flag = false ORDER BY version DESC")
    List<BpmProcessDefinition> findAllVersions(@Param("tenantId") Long tenantId, @Param("processKey") String processKey);

    /**
     * Find deployed processes.
     */
    @Select("SELECT * FROM ab_bpm_process_definition WHERE tenant_id = #{tenantId} AND status = 'deployed' AND deleted_flag = false ORDER BY process_name")
    List<BpmProcessDefinition> findDeployed(@Param("tenantId") Long tenantId);

    /**
     * Find by plugin PID.
     */
    @Select("SELECT * FROM ab_bpm_process_definition WHERE plugin_pid = #{pluginPid} AND deleted_flag = false")
    List<BpmProcessDefinition> findByPluginPid(@Param("pluginPid") String pluginPid);

    /**
     * Find by status.
     */
    @Select("SELECT * FROM ab_bpm_process_definition WHERE tenant_id = #{tenantId} AND status = #{status} AND deleted_flag = false")
    List<BpmProcessDefinition> findByStatus(@Param("tenantId") Long tenantId, @Param("status") String status);

    /**
     * Find by category.
     */
    @Select("SELECT * FROM ab_bpm_process_definition WHERE tenant_id = #{tenantId} AND category = #{category} AND deleted_flag = false")
    List<BpmProcessDefinition> findByCategory(@Param("tenantId") Long tenantId, @Param("category") String category);

    /**
     * Update status.
     */
    @Update("UPDATE ab_bpm_process_definition SET status = #{status}, updated_at = NOW() WHERE pid = #{pid}")
    int updateStatus(@Param("pid") String pid, @Param("status") String status);

    /**
     * Update deployment ID.
     */
    @Update("UPDATE ab_bpm_process_definition SET deployment_id = #{deploymentId}, deployed_at = NOW(), status = 'deployed', updated_at = NOW() WHERE pid = #{pid}")
    int updateDeployment(@Param("pid") String pid, @Param("deploymentId") String deploymentId);

    /**
     * Mark all versions as not current.
     */
    @Update("UPDATE ab_bpm_process_definition SET is_current = FALSE, updated_at = NOW() WHERE tenant_id = #{tenantId} AND process_key = #{processKey} AND is_current = TRUE")
    int clearCurrentVersion(@Param("tenantId") Long tenantId, @Param("processKey") String processKey);

    /**
     * Check if process key exists.
     */
    @Select("SELECT COUNT(*) > 0 FROM ab_bpm_process_definition WHERE tenant_id = #{tenantId} AND process_key = #{processKey} AND deleted_flag = false")
    boolean existsByProcessKey(@Param("tenantId") Long tenantId, @Param("processKey") String processKey);

    /**
     * Get next version number.
     */
    @Select("SELECT COALESCE(MAX(version), 0) + 1 FROM ab_bpm_process_definition WHERE tenant_id = #{tenantId} AND process_key = #{processKey}")
    int getNextVersion(@Param("tenantId") Long tenantId, @Param("processKey") String processKey);
}
