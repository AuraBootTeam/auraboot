package com.auraboot.framework.bpm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.auraboot.framework.bpm.entity.BpmRule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface BpmRuleMapper extends BaseMapper<BpmRule> {

    @Select("SELECT * FROM ab_bpm_rule WHERE tenant_id = #{tenantId} AND rule_code = #{ruleCode} AND enabled = TRUE AND deleted_flag = false")
    BpmRule findByCode(@Param("tenantId") Long tenantId, @Param("ruleCode") String ruleCode);

    @Select("SELECT * FROM ab_bpm_rule WHERE pid = #{pid} AND deleted_flag = false")
    BpmRule findByPid(@Param("pid") String pid);

    @Select("SELECT * FROM ab_bpm_rule WHERE tenant_id = #{tenantId} AND deleted_flag = false ORDER BY rule_code")
    List<BpmRule> findAll(@Param("tenantId") Long tenantId);

    @Select("SELECT * FROM ab_bpm_rule WHERE tenant_id = #{tenantId} AND rule_type = #{ruleType} AND enabled = TRUE AND deleted_flag = false ORDER BY rule_code")
    List<BpmRule> findByType(@Param("tenantId") Long tenantId, @Param("ruleType") String ruleType);
}
