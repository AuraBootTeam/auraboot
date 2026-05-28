package com.auraboot.framework.semantic.mapper;

import com.auraboot.framework.semantic.entity.AbSemanticMetric;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AbSemanticMetricMapper extends BaseMapper<AbSemanticMetric> {

    @Select("SELECT * FROM ab_semantic_metric "
          + "WHERE tenant_id = #{tenantId} AND pid = #{pid} AND deleted_flag = FALSE LIMIT 1")
    AbSemanticMetric findByPid(@Param("tenantId") Long tenantId, @Param("pid") String pid);

    @Select("SELECT * FROM ab_semantic_metric "
          + "WHERE tenant_id = #{tenantId} AND code = #{code} AND version = #{version} "
          + "AND deleted_flag = FALSE LIMIT 1")
    AbSemanticMetric findByCode(@Param("tenantId") Long tenantId,
                                 @Param("code") String code,
                                 @Param("version") String version);

    @Select("SELECT * FROM ab_semantic_metric "
          + "WHERE tenant_id = #{tenantId} AND semantic_model_pid = #{modelPid} "
          + "AND status = 'ACTIVE' AND deleted_flag = FALSE ORDER BY code")
    List<AbSemanticMetric> listActiveByModel(@Param("tenantId") Long tenantId,
                                              @Param("modelPid") String modelPid);

    @Select("SELECT * FROM ab_semantic_metric "
          + "WHERE tenant_id = #{tenantId} AND metric_type = #{metricType} "
          + "AND deleted_flag = FALSE ORDER BY code")
    List<AbSemanticMetric> listByType(@Param("tenantId") Long tenantId,
                                       @Param("metricType") String metricType);
}
