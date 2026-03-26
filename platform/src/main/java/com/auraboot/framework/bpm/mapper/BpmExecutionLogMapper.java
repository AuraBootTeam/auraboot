package com.auraboot.framework.bpm.mapper;

import com.auraboot.framework.bpm.entity.BpmExecutionLog;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * BPM execution log mapper.
 * Provides database access for ab_bpm_execution_log table.
 *
 * Note: JSONB columns (input_data, output_data) require autoResultMap.
 * Use default methods with selectList() instead of @Select for proper type handler resolution.
 */
@Mapper
public interface BpmExecutionLogMapper extends BaseMapper<BpmExecutionLog> {

    default List<BpmExecutionLog> findByExecutionId(String executionId) {
        return selectList(new QueryWrapper<BpmExecutionLog>()
                .eq("execution_id", executionId)
                .orderByAsc("created_at"));
    }

    default List<BpmExecutionLog> findByExecutionIdAndNodeId(String executionId, String nodeId) {
        return selectList(new QueryWrapper<BpmExecutionLog>()
                .eq("execution_id", executionId)
                .eq("node_id", nodeId)
                .orderByAsc("created_at"));
    }

    default List<BpmExecutionLog> findFailedNodes(String executionId) {
        return selectList(new QueryWrapper<BpmExecutionLog>()
                .eq("execution_id", executionId)
                .eq("event_type", "node_failure")
                .orderByDesc("created_at"));
    }

    @Select("SELECT COUNT(*) FROM ab_bpm_execution_log " +
            "WHERE execution_id = #{executionId} AND event_type = #{eventType}")
    int countByEventType(@Param("executionId") String executionId,
                         @Param("eventType") String eventType);
}
