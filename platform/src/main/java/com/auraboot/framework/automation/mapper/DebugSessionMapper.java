package com.auraboot.framework.automation.mapper;

import com.auraboot.framework.automation.entity.DebugSession;
import com.auraboot.framework.automation.typehandler.ActionResultsTypeHandler;
import com.auraboot.framework.automation.typehandler.BreakpointsTypeHandler;
import com.auraboot.framework.automation.typehandler.TriggerPayloadTypeHandler;
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

    @Results(id = "DebugSessionResultMap", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "pid", property = "pid"),
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "automation_id", property = "automationId"),
            @Result(column = "record_pid", property = "recordPid"),
            @Result(column = "status", property = "status"),
            @Result(column = "current_action_index", property = "currentActionIndex"),
            @Result(column = "breakpoints", property = "breakpoints", typeHandler = BreakpointsTypeHandler.class),
            @Result(column = "execution_context", property = "executionContext", typeHandler = TriggerPayloadTypeHandler.class),
            @Result(column = "action_results", property = "actionResults", typeHandler = ActionResultsTypeHandler.class),
            @Result(column = "trigger_payload", property = "triggerPayload", typeHandler = TriggerPayloadTypeHandler.class),
            @Result(column = "error_message", property = "errorMessage"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt"),
            @Result(column = "created_by", property = "createdBy")
    })
    @Select("SELECT * FROM ab_automation_debug_session WHERE pid = #{pid}")
    DebugSession findByPid(@Param("pid") String pid);

    @ResultMap("DebugSessionResultMap")
    @Select("""
        SELECT * FROM ab_automation_debug_session
        WHERE automation_id = #{automationId}
          AND status IN ('paused', 'running')
        ORDER BY created_at DESC
        LIMIT 1
        """)
    DebugSession findActiveByAutomationId(@Param("automationId") String automationId);

    @ResultMap("DebugSessionResultMap")
    @Select("""
        SELECT * FROM ab_automation_debug_session
        WHERE automation_id = #{automationId}
        ORDER BY created_at DESC
        LIMIT #{limit}
        """)
    List<DebugSession> findByAutomationId(@Param("automationId") String automationId, @Param("limit") int limit);

    @Insert("""
        INSERT INTO ab_automation_debug_session (
            pid, tenant_id, automation_id, record_pid, status,
            current_action_index, breakpoints, execution_context,
            action_results, trigger_payload, error_message,
            created_at, updated_at, created_by
        ) VALUES (
            #{pid}, #{tenantId}, #{automationId}, #{recordPid}, #{status},
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
