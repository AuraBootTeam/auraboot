package com.auraboot.framework.bpm.mapper;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.auraboot.framework.bpm.entity.BpmNodeHook;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * BPM node hook mapper.
 *
 * Note: hookConfig is a JSONB column requiring autoResultMap for proper type handler resolution.
 * Use default methods with selectList() instead of @Select.
 */
@Mapper
public interface BpmNodeHookMapper extends BaseMapper<BpmNodeHook> {

    default List<BpmNodeHook> findHooks(Long tenantId, String processKey, String nodeId, String hookType) {
        return selectList(new QueryWrapper<BpmNodeHook>()
                .eq("tenant_id", tenantId)
                .eq("process_key", processKey)
                .eq("node_id", nodeId)
                .eq("hook_type", hookType)
                .eq("enabled", true)
                .orderByAsc("execution_order"));
    }

    default BpmNodeHook findByPid(String pid) {
        return selectOne(new QueryWrapper<BpmNodeHook>()
                .eq("pid", pid));
    }

    default List<BpmNodeHook> findByProcessKey(Long tenantId, String processKey) {
        return selectList(new QueryWrapper<BpmNodeHook>()
                .eq("tenant_id", tenantId)
                .eq("process_key", processKey)
                .orderByAsc("node_id", "hook_type", "execution_order"));
    }
}
