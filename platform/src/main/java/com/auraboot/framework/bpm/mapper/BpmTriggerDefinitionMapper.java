package com.auraboot.framework.bpm.mapper;

import com.auraboot.framework.bpm.entity.BpmTriggerDefinition;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * BPM trigger definition mapper.
 * Provides database access for ab_bpm_trigger_definition table.
 *
 * Note: trigger_config is a JSONB column requiring autoResultMap for proper type handler resolution.
 * Use default methods with selectList() instead of @Select.
 */
@Mapper
public interface BpmTriggerDefinitionMapper extends BaseMapper<BpmTriggerDefinition> {

    default List<BpmTriggerDefinition> findByProcessKey(Long tenantId, String processKey) {
        return selectList(new QueryWrapper<BpmTriggerDefinition>()
                .eq("tenant_id", tenantId)
                .eq("process_key", processKey)
                .orderByDesc("created_at"));
    }

    default BpmTriggerDefinition findByPid(String pid) {
        return selectOne(new QueryWrapper<BpmTriggerDefinition>()
                .eq("pid", pid));
    }

    default List<BpmTriggerDefinition> findEnabledScheduledTriggers() {
        return selectList(new QueryWrapper<BpmTriggerDefinition>()
                .eq("status", StatusConstants.ENABLED)
                .eq("trigger_type", "scheduled"));
    }
}
