package com.auraboot.framework.automation.mapper;

import com.auraboot.framework.automation.entity.AutomationLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.time.Instant;
import java.util.List;

/**
 * AutomationLog Mapper interface
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Mapper
public interface AutomationLogMapper extends BaseMapper<AutomationLog> {

    /**
     * Find log by PID
     */
    @Select("SELECT * FROM ab_automation_log WHERE pid = #{pid}")
    AutomationLog findByPid(@Param("pid") String pid);

    /**
     * Find logs by automation ID
     */
    @Select("""
        SELECT * FROM ab_automation_log
        WHERE automation_id = #{automationId}
        ORDER BY created_at DESC
        LIMIT #{limit}
        """)
    List<AutomationLog> findByAutomationId(
            @Param("automationId") String automationId,
            @Param("limit") int limit);

    /**
     * Find recent logs by status
     */
    @Select("""
        SELECT * FROM ab_automation_log
        WHERE status = #{status}
        ORDER BY created_at DESC
        LIMIT #{limit}
        """)
    List<AutomationLog> findByStatus(
            @Param("status") String status,
            @Param("limit") int limit);

    /**
     * Find logs by record ID (what automations were triggered for a record)
     */
    @Select("""
        SELECT * FROM ab_automation_log
        WHERE trigger_record_id = #{recordId}
        ORDER BY created_at DESC
        LIMIT #{limit}
        """)
    List<AutomationLog> findByTriggerRecordId(
            @Param("recordId") String recordId,
            @Param("limit") int limit);

    /**
     * Count logs by automation ID and status
     */
    @Select("""
        SELECT COUNT(*) FROM ab_automation_log
        WHERE automation_id = #{automationId}
          AND status = #{status}
        """)
    long countByAutomationIdAndStatus(
            @Param("automationId") String automationId,
            @Param("status") String status);

    /**
     * Delete old logs (cleanup)
     */
    @Delete("""
        DELETE FROM ab_automation_log
        WHERE created_at < #{before}
        """)
    int deleteOlderThan(@Param("before") Instant before);

    /**
     * Update log status
     */
    @Update("""
        UPDATE ab_automation_log
        SET status = #{status},
            started_at = #{startedAt},
            completed_at = #{completedAt},
            error_message = #{errorMessage},
            action_results = #{actionResults, typeHandler=com.auraboot.framework.automation.typehandler.ActionResultsTypeHandler}
        WHERE pid = #{pid}
        """)
    int updateStatus(AutomationLog log);

    /**
     * Insert with JSONB handling
     */
    @Insert("""
        INSERT INTO ab_automation_log (
            pid, tenant_id, automation_id, trigger_type, trigger_record_id,
            trigger_payload, status, started_at, completed_at, error_message,
            action_results, created_at
        ) VALUES (
            #{pid}, #{tenantId}, #{automationId}, #{triggerType}, #{triggerRecordId},
            #{triggerPayload, typeHandler=com.auraboot.framework.automation.typehandler.TriggerPayloadTypeHandler},
            #{status}, #{startedAt}, #{completedAt}, #{errorMessage},
            #{actionResults, typeHandler=com.auraboot.framework.automation.typehandler.ActionResultsTypeHandler},
            #{createdAt}
        )
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertLog(AutomationLog log);
}
