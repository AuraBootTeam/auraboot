package com.auraboot.framework.semantic.mapper;

import com.auraboot.framework.semantic.entity.AbSemanticModel;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AbSemanticModelMapper extends BaseMapper<AbSemanticModel> {

    @Select("SELECT * FROM ab_semantic_model "
          + "WHERE tenant_id = #{tenantId} AND pid = #{pid} AND deleted_flag = FALSE LIMIT 1")
    AbSemanticModel findByPid(@Param("tenantId") Long tenantId, @Param("pid") String pid);

    @Select("SELECT * FROM ab_semantic_model "
          + "WHERE tenant_id = #{tenantId} AND plugin_code = #{pluginCode} "
          + "AND code = #{code} AND version = #{version} AND deleted_flag = FALSE LIMIT 1")
    AbSemanticModel findByCode(@Param("tenantId") Long tenantId,
                               @Param("pluginCode") String pluginCode,
                               @Param("code") String code,
                               @Param("version") String version);

    @Select("SELECT * FROM ab_semantic_model "
          + "WHERE tenant_id = #{tenantId} AND status = 'ACTIVE' AND deleted_flag = FALSE "
          + "ORDER BY plugin_code, code")
    List<AbSemanticModel> listActiveByTenant(@Param("tenantId") Long tenantId);

    /**
     * Find the first ACTIVE semantic model whose {@code model_ref} matches the given name.
     * Used by dbt lineage ingest to emit cross-link edges from DBT_MODEL → SEMANTIC_MODEL.
     */
    @Select("SELECT * FROM ab_semantic_model "
          + "WHERE tenant_id = #{tenantId} AND model_ref = #{modelRef} "
          + "AND status = 'ACTIVE' AND deleted_flag = FALSE LIMIT 1")
    AbSemanticModel findByModelRef(@Param("tenantId") Long tenantId, @Param("modelRef") String modelRef);
}
