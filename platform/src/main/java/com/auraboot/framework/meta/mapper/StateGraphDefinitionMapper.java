package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.StateGraphDefinition;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * State Graph Definition Mapper.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Mapper
public interface StateGraphDefinitionMapper extends BaseMapper<StateGraphDefinition> {

    @Insert("""
        INSERT INTO ab_state_graph_definition
        (pid, tenant_id, code, display_name, description, model_code, state_field,
         nodes, transitions, extension,
         version, semver, is_current, row_version, status, deleted_flag,
         created_at, updated_at)
        VALUES
        (#{pid}, #{tenantId}, #{code}, #{displayName}, #{description}, #{modelCode}, #{stateField},
         #{nodes, typeHandler=com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler},
         #{transitions, typeHandler=com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler},
         #{extension, typeHandler=com.auraboot.framework.application.database.mybatis.ExtensionTypeHandler},
         #{version}, #{semver}, #{isCurrent}, #{rowVersion}, #{status}, #{deletedFlag},
         #{createdAt}, #{updatedAt})
        ON CONFLICT (tenant_id, code, version) DO NOTHING
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertIdempotent(StateGraphDefinition definition);

    @Select("SELECT * FROM ab_state_graph_definition WHERE pid = #{pid} AND deleted_flag = false")
    StateGraphDefinition findByPid(@Param("pid") String pid);

    // Note: tenant_id condition is automatically added by TenantLineInnerInterceptor
    @Select("""
        SELECT * FROM ab_state_graph_definition
        WHERE code = #{code} AND is_current = TRUE AND deleted_flag = false
        """)
    StateGraphDefinition findCurrentByCode(@Param("code") String code);

    // Note: tenant_id condition is automatically added by TenantLineInnerInterceptor
    @Select("""
        SELECT * FROM ab_state_graph_definition
        WHERE model_code = #{modelCode}
        AND is_current = TRUE AND deleted_flag = false
        ORDER BY code
        """)
    List<StateGraphDefinition> findByModelCode(@Param("modelCode") String modelCode);

    // Note: tenant_id condition is automatically added by TenantLineInnerInterceptor
    @Select("""
        SELECT * FROM ab_state_graph_definition
        WHERE model_code = #{modelCode}
        AND status = 'published' AND is_current = TRUE AND deleted_flag = false
        LIMIT 1
        """)
    StateGraphDefinition findPublishedByModelCode(@Param("modelCode") String modelCode);

    // Note: tenant_id condition is automatically added by TenantLineInnerInterceptor
    @Update("UPDATE ab_state_graph_definition SET is_current = FALSE WHERE code = #{code}")
    int markAsNotCurrent(@Param("code") String code);

    @Update("UPDATE ab_state_graph_definition SET status = #{status}, updated_at = NOW() WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    @Update("""
        UPDATE ab_state_graph_definition
        SET is_current = TRUE, status = #{status}, updated_at = NOW()
        WHERE id = #{id}
        """)
    int publishById(@Param("id") Long id, @Param("status") String status);

    @Update("UPDATE ab_state_graph_definition SET deleted_flag = TRUE, updated_at = NOW() WHERE pid = #{pid}")
    int softDelete(@Param("pid") String pid);
}
