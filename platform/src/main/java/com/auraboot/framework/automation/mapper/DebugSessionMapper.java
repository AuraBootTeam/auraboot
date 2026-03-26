package com.auraboot.framework.automation.mapper;

import com.auraboot.framework.automation.entity.DebugSession;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * Debug Session Mapper
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Mapper
public interface DebugSessionMapper extends BaseMapper<DebugSession> {

    @Select("SELECT * FROM ab_automation_debug_session WHERE pid = #{pid}")
    DebugSession findByPid(@Param("pid") String pid);

    @Select("""
        SELECT * FROM ab_automation_debug_session
        WHERE automation_id = #{automationId}
          AND status IN ('paused', 'running')
        ORDER BY created_at DESC
        LIMIT 1
        """)
    DebugSession findActiveByAutomationId(@Param("automationId") String automationId);

    @Select("""
        SELECT * FROM ab_automation_debug_session
        WHERE automation_id = #{automationId}
        ORDER BY created_at DESC
        LIMIT #{limit}
        """)
    List<DebugSession> findByAutomationId(@Param("automationId") String automationId, @Param("limit") int limit);

    @Insert("""
        INSERT INTO ab_automation_debug_session (
            pid, tenant_id, automation_id, record_id, status,
            current_action_index, breakpoints, execution_context,
            action_results, trigger_payload, error_message,
            created_at, updated_at, created_by
        ) VALUES (
            #{pid}, #{tenantId}, #{automationId}, #{recordId}, #{status},
            #{currentActionIndex},
            #{breakpoints, typeHandler=com.auraboot.framework.automation.typehandler.BreakpointsTypeHandler},
            #{executionContext, typeHandler=com.auraboot.framework.automation.typehandler.TriggerPayloadTypeHandler},
            #{actionResults, typeHandler=com.auraboot.framework.automation.typehandler.ActionResultsTypeHandler},
            #{triggerPayload, typeHandler=com.auraboot.framework.automation.typehandler.TriggerPayloadTypeHandler},
            #{errorMessage}, #{createdAt}, #{updatedAt}, #{createdBy}
        )
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertSession(DebugSession session);

    @Update("""
        UPDATE ab_automation_debug_session SET
            status = #{status},
            current_action_index = #{currentActionIndex},
            breakpoints = #{breakpoints, typeHandler=com.auraboot.framework.automation.typehandler.BreakpointsTypeHandler},
            execution_context = #{executionContext, typeHandler=com.auraboot.framework.automation.typehandler.TriggerPayloadTypeHandler},
            action_results = #{actionResults, typeHandler=com.auraboot.framework.automation.typehandler.ActionResultsTypeHandler},
            error_message = #{errorMessage},
            updated_at = NOW()
        WHERE pid = #{pid}
        """)
    int updateSession(DebugSession session);
}
