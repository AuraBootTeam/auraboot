package com.auraboot.framework.agent.mapper;

import com.auraboot.framework.agent.entity.AgentDefinition;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AgentDefinitionMapper extends BaseMapper<AgentDefinition> {

    @Select("SELECT * FROM ab_agent_definition WHERE tenant_id = #{tenantId} AND is_employee = TRUE AND status = 'active' AND (deleted_flag = FALSE OR deleted_flag IS NULL) ORDER BY name")
    List<AgentDefinition> findEmployees(@Param("tenantId") Long tenantId);
}
