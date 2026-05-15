package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.CommandDefinition;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * Command Definition Mapper
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Mapper
public interface CommandDefinitionMapper extends BaseMapper<CommandDefinition> {

    @Insert("""
        INSERT INTO ab_command_definition
        (pid, tenant_id, code, display_name, description, model_code,
         input_schema, target_models, execution_config, extension,
         cmd_risk_level, plugin_pid,
         version, semver, is_current, row_version, status, deleted_flag,
         created_at, updated_at)
        VALUES
        (#{pid}, #{tenantId}, #{code}, #{displayName}, #{description}, #{modelCode},
         #{inputSchema, typeHandler=com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler},
         #{targetModels, typeHandler=com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler},
         #{executionConfig, typeHandler=com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler},
         #{extension, typeHandler=com.auraboot.framework.application.database.mybatis.ExtensionTypeHandler},
         #{cmdRiskLevel}, #{pluginPid},
         #{version}, #{semver}, #{isCurrent}, #{rowVersion}, #{status}, #{deletedFlag},
         #{createdAt}, #{updatedAt})
        ON CONFLICT (tenant_id, code, version) DO NOTHING
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertIdempotent(CommandDefinition commandDefinition);

    @Select("SELECT * FROM ab_command_definition WHERE pid = #{pid} AND deleted_flag = false")
    CommandDefinition findByPid(@Param("pid") String pid);

    // Note: tenant_id condition is automatically added by TenantLineInnerInterceptor
    @Select("SELECT * FROM ab_command_definition WHERE code = #{code} AND is_current = TRUE AND deleted_flag = false ORDER BY id DESC LIMIT 1")
    CommandDefinition findCurrentByCode(@Param("code") String code);

    // Note: tenant_id condition is automatically added by TenantLineInnerInterceptor
    @Select("SELECT * FROM ab_command_definition WHERE model_code = #{modelCode} AND is_current = TRUE AND deleted_flag = false ORDER BY code")
    List<CommandDefinition> findByModelCode(@Param("modelCode") String modelCode);

    // Note: tenant_id condition is automatically added by TenantLineInnerInterceptor
    @Update("UPDATE ab_command_definition SET is_current = FALSE WHERE code = #{code}")
    int markAsNotCurrent(@Param("code") String code);

    @Update("UPDATE ab_command_definition SET status = #{status}, updated_at = NOW() WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    @Update("UPDATE ab_command_definition SET deleted_flag = TRUE, updated_at = NOW() WHERE pid = #{pid}")
    int softDelete(@Param("pid") String pid);

    // ==================== Plugin Import Support ====================

    /**
     * Update command definition in place for plugin reimport.
     */
    @Update("""
        UPDATE ab_command_definition SET
            display_name = #{displayName},
            description = #{description},
            model_code = #{modelCode},
            input_schema = #{inputSchema}::jsonb,
            target_models = #{targetModels}::jsonb,
            execution_config = #{executionConfig}::jsonb,
            extension = #{extension}::jsonb,
            cmd_risk_level = #{cmdRiskLevel},
            plugin_pid = #{pluginPid},
            updated_at = NOW()
        WHERE pid = #{pid} AND tenant_id = #{tenantId}
        """)
    int updateForPluginImport(@Param("displayName") String displayName,
                              @Param("description") String description,
                              @Param("modelCode") String modelCode,
                              @Param("inputSchema") String inputSchema,
                              @Param("targetModels") String targetModels,
                              @Param("executionConfig") String executionConfig,
                              @Param("extension") String extension,
                              @Param("cmdRiskLevel") String cmdRiskLevel,
                              @Param("pluginPid") String pluginPid,
                              @Param("pid") String pid,
                              @Param("tenantId") Long tenantId);

    /**
     * Archive command by pid (fallback delete for plugin uninstall).
     */
    @Update("UPDATE ab_command_definition SET status = 'archived', deleted_flag = TRUE WHERE pid = #{pid}")
    int archiveByPid(@Param("pid") String pid);
}
