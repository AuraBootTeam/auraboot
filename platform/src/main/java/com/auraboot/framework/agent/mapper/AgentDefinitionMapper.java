package com.auraboot.framework.agent.mapper;

import com.auraboot.framework.agent.entity.AgentDefinition;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * These two finders are wrapper queries, not {@code @Select} annotations, and must stay that way.
 *
 * <p>An annotated {@code @Select("SELECT * FROM ab_agent_definition ...")} does not use the
 * entity's {@code autoResultMap}, so the {@code @TableField(typeHandler = ...)} declarations on
 * {@code allowedModels} / {@code allowedOperations} are ignored and MyBatis resolves a handler
 * from the global registry by java type instead. The handler it finds for {@code List} is
 * {@code DataSourceItemBeanTypeHandler} — registered for {@code Dict.items} — which tries to build
 * a {@code DataSourceItemBean} out of the string {@code "query"} and throws. Reading any agent
 * whose {@code allowed_operations} is non-empty then fails with an uncategorized SQLException,
 * which surfaces as a 500 far from its cause. Wrapper queries go through the entity result map and
 * honour the declared handlers.
 *
 * <p>Soft deletes are filtered by the global {@code logic-delete-field: deletedFlag} config, which
 * appends {@code deleted_flag = false} to wrapper queries — so the predicate the replaced SQL
 * spelled out is not repeated here. One semantic difference is deliberate: that SQL also returned
 * rows whose {@code deleted_flag} was NULL, and the injected predicate does not. The column
 * defaults to {@code false}, so only a row inserted with an explicit NULL is affected.
 */
@Mapper
public interface AgentDefinitionMapper extends BaseMapper<AgentDefinition> {

    default List<AgentDefinition> findEmployees(Long tenantId) {
        return selectList(new LambdaQueryWrapper<AgentDefinition>()
                .eq(AgentDefinition::getTenantId, tenantId)
                .isNotNull(AgentDefinition::getEmployeeId)
                .eq(AgentDefinition::getStatus, "active")
                .orderByAsc(AgentDefinition::getName));
    }

    default AgentDefinition findByPid(String pid) {
        return selectOne(new LambdaQueryWrapper<AgentDefinition>()
                .eq(AgentDefinition::getPid, pid)
                .last("LIMIT 1"));
    }
}
