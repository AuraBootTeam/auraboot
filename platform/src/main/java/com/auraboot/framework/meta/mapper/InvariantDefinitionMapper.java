package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.InvariantDefinition;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * Invariant Definition Mapper.
 *
 * @author AuraBoot Team
 * @since 2.5.0
 */
@Mapper
public interface InvariantDefinitionMapper extends BaseMapper<InvariantDefinition> {

    @Insert("""
        INSERT INTO ab_invariant_definition
        (pid, tenant_id, code, display_name, description, expression,
         invariant_type, severity, scope_type, scope_ref, model_code, enabled,
         extension, version, semver, is_current, row_version, status, deleted_flag,
         created_at, updated_at)
        VALUES
        (#{pid}, #{tenantId}, #{code}, #{displayName}, #{description}, #{expression},
         #{invariantType}, #{severity}, #{scopeType}, #{scopeRef}, #{modelCode}, #{enabled},
         #{extension, typeHandler=com.auraboot.framework.application.database.mybatis.ExtensionTypeHandler},
         #{version}, #{semver}, #{isCurrent}, #{rowVersion}, #{status}, #{deletedFlag},
         #{createdAt}, #{updatedAt})
        ON CONFLICT (tenant_id, code, version) DO NOTHING
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertIdempotent(InvariantDefinition definition);

    @Select("SELECT * FROM ab_invariant_definition WHERE pid = #{pid} AND deleted_flag = false")
    InvariantDefinition findByPid(@Param("pid") String pid);

    @Select("""
        SELECT * FROM ab_invariant_definition
        WHERE tenant_id = #{tenantId} AND code = #{code} AND is_current = TRUE AND deleted_flag = false
        """)
    InvariantDefinition findCurrentByCode(@Param("tenantId") Long tenantId, @Param("code") String code);

    @Select("""
        SELECT * FROM ab_invariant_definition
        WHERE tenant_id = #{tenantId} AND model_code = #{modelCode}
        AND is_current = TRUE AND deleted_flag = false
        ORDER BY code
        """)
    List<InvariantDefinition> findByModelCode(@Param("tenantId") Long tenantId, @Param("modelCode") String modelCode);

    @Select("""
        SELECT * FROM ab_invariant_definition
        WHERE tenant_id = #{tenantId} AND invariant_type = #{invariantType}
        AND scope_type = #{scopeType} AND scope_ref = #{scopeRef}
        AND status = 'published' AND is_current = TRUE AND enabled = TRUE AND deleted_flag = false
        """)
    List<InvariantDefinition> findPublishedByScope(@Param("tenantId") Long tenantId,
                                                    @Param("invariantType") String invariantType,
                                                    @Param("scopeType") String scopeType,
                                                    @Param("scopeRef") String scopeRef);

    @Select("""
        SELECT * FROM ab_invariant_definition
        WHERE tenant_id = #{tenantId} AND invariant_type = 'always'
        AND model_code = #{modelCode}
        AND status = 'published' AND is_current = TRUE AND enabled = TRUE AND deleted_flag = false
        """)
    List<InvariantDefinition> findAlwaysByModelCode(@Param("tenantId") Long tenantId,
                                                     @Param("modelCode") String modelCode);

    @Select("""
        SELECT * FROM ab_invariant_definition
        WHERE invariant_type = 'always'
        AND status = 'published' AND is_current = TRUE AND enabled = TRUE AND deleted_flag = false
        """)
    List<InvariantDefinition> findAllPublishedAlways();

    @Update("UPDATE ab_invariant_definition SET is_current = FALSE WHERE tenant_id = #{tenantId} AND code = #{code}")
    int markAsNotCurrent(@Param("tenantId") Long tenantId, @Param("code") String code);

    @Update("""
        UPDATE ab_invariant_definition
        SET is_current = TRUE, status = #{status}, updated_at = NOW()
        WHERE id = #{id}
        """)
    int publishById(@Param("id") Long id, @Param("status") String status);

    @Update("UPDATE ab_invariant_definition SET deleted_flag = TRUE, updated_at = NOW() WHERE pid = #{pid}")
    int softDelete(@Param("pid") String pid);
}
