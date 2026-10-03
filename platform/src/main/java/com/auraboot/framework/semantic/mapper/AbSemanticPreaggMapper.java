package com.auraboot.framework.semantic.mapper;

import com.auraboot.framework.semantic.entity.AbSemanticPreagg;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface AbSemanticPreaggMapper extends BaseMapper<AbSemanticPreagg> {

    @Update("UPDATE ab_semantic_preagg SET deleted_flag = TRUE, updated_at = #{updatedAt} "
          + "WHERE tenant_id = #{tenantId} AND pid = #{pid} AND deleted_flag = FALSE")
    int softDelete(@Param("tenantId") Long tenantId, @Param("pid") String pid,
                   @Param("updatedAt") OffsetDateTime updatedAt);

    @Select("SELECT * FROM ab_semantic_preagg "
          + "WHERE tenant_id = #{tenantId} AND pid = #{pid} AND deleted_flag = FALSE LIMIT 1")
    AbSemanticPreagg findByPid(@Param("tenantId") Long tenantId, @Param("pid") String pid);

    @Select("SELECT * FROM ab_semantic_preagg "
          + "WHERE tenant_id = #{tenantId} AND deleted_flag = FALSE ORDER BY created_at DESC")
    List<AbSemanticPreagg> listByTenant(@Param("tenantId") Long tenantId);

    @Select("SELECT * FROM ab_semantic_preagg "
          + "WHERE deleted_flag = FALSE ORDER BY pid")
    List<AbSemanticPreagg> listAllAcrossTenants();
}
