package com.auraboot.framework.semantic.mapper;

import com.auraboot.framework.semantic.entity.AbSemanticExposure;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AbSemanticExposureMapper extends BaseMapper<AbSemanticExposure> {

    @Select("SELECT * FROM ab_semantic_exposure "
          + "WHERE tenant_id = #{tenantId} AND pid = #{pid} AND deleted_flag = FALSE LIMIT 1")
    AbSemanticExposure findByPid(@Param("tenantId") Long tenantId, @Param("pid") String pid);

    @Select("SELECT * FROM ab_semantic_exposure "
          + "WHERE tenant_id = #{tenantId} AND code = #{code} AND deleted_flag = FALSE LIMIT 1")
    AbSemanticExposure findByCode(@Param("tenantId") Long tenantId, @Param("code") String code);

    @Select("SELECT * FROM ab_semantic_exposure "
          + "WHERE tenant_id = #{tenantId} AND exposure_type = #{type} "
          + "AND deleted_flag = FALSE ORDER BY code")
    List<AbSemanticExposure> listByType(@Param("tenantId") Long tenantId,
                                         @Param("type") String type);

    @Select("SELECT * FROM ab_semantic_exposure "
          + "WHERE tenant_id = #{tenantId} AND owner_user_id = #{userId} "
          + "AND deleted_flag = FALSE ORDER BY code")
    List<AbSemanticExposure> listByOwner(@Param("tenantId") Long tenantId,
                                          @Param("userId") Long userId);
}
