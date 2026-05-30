package com.auraboot.framework.dataquality.ge.mapper;

import com.auraboot.framework.dataquality.ge.entity.AbDataQualityValidationRun;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Mapper for {@code ab_dataquality_validation_run}.
 */
@Mapper
public interface AbDataQualityValidationRunMapper extends BaseMapper<AbDataQualityValidationRun> {

    @Select("SELECT * FROM ab_dataquality_validation_run "
          + "WHERE tenant_id = #{tenantId} AND suite_pid = #{suitePid} "
          + "AND deleted_flag = FALSE "
          + "ORDER BY started_at DESC LIMIT 50")
    List<AbDataQualityValidationRun> listBySuite(@Param("tenantId") Long tenantId,
                                                  @Param("suitePid") String suitePid);
}
