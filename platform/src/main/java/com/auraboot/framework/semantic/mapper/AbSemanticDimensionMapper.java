package com.auraboot.framework.semantic.mapper;

import com.auraboot.framework.semantic.entity.AbSemanticDimension;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AbSemanticDimensionMapper extends BaseMapper<AbSemanticDimension> {

    @Select("SELECT * FROM ab_semantic_dimension "
          + "WHERE tenant_id = #{tenantId} AND semantic_model_pid = #{modelPid} "
          + "AND code = #{code} AND deleted_flag = FALSE LIMIT 1")
    AbSemanticDimension findByCode(@Param("tenantId") Long tenantId,
                                    @Param("modelPid") String modelPid,
                                    @Param("code") String code);

    @Select("SELECT * FROM ab_semantic_dimension "
          + "WHERE tenant_id = #{tenantId} AND semantic_model_pid = #{modelPid} "
          + "AND deleted_flag = FALSE ORDER BY code")
    List<AbSemanticDimension> listByModel(@Param("tenantId") Long tenantId,
                                           @Param("modelPid") String modelPid);

    @Select("SELECT * FROM ab_semantic_dimension "
          + "WHERE tenant_id = #{tenantId} AND semantic_model_pid = #{modelPid} "
          + "AND primary_time = TRUE AND deleted_flag = FALSE LIMIT 1")
    AbSemanticDimension findPrimaryTime(@Param("tenantId") Long tenantId,
                                         @Param("modelPid") String modelPid);
}
