package com.auraboot.framework.dataquality.ge.mapper;

import com.auraboot.framework.dataquality.ge.entity.AbDataQualityExpectationSuite;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Mapper for {@code ab_dataquality_expectation_suite}.
 */
@Mapper
public interface AbDataQualityExpectationSuiteMapper extends BaseMapper<AbDataQualityExpectationSuite> {

    @Select("SELECT * FROM ab_dataquality_expectation_suite "
          + "WHERE tenant_id = #{tenantId} AND pid = #{pid} AND deleted_flag = FALSE LIMIT 1")
    AbDataQualityExpectationSuite findByPid(@Param("tenantId") Long tenantId, @Param("pid") String pid);

    @Select("SELECT * FROM ab_dataquality_expectation_suite "
          + "WHERE tenant_id = #{tenantId} AND suite_name = #{suiteName} AND deleted_flag = FALSE LIMIT 1")
    AbDataQualityExpectationSuite findBySuiteName(@Param("tenantId") Long tenantId,
                                                   @Param("suiteName") String suiteName);
}
