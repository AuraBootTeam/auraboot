package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.CommandAuditLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * Command Audit Log Mapper
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Mapper
public interface CommandAuditLogMapper extends BaseMapper<CommandAuditLog> {

    @Insert("""
        INSERT INTO ab_command_audit_log
        (tenant_id, command_code, command_pid, user_id, request_payload,
         execution_result, success, error_message, execution_time_ms,
         phase_reached, phase_timings, ip_address, created_at)
        VALUES
        (#{tenantId}, #{commandCode}, #{commandPid}, #{userId},
         #{requestPayload, typeHandler=com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler},
         #{executionResult, typeHandler=com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler},
         #{success}, #{errorMessage}, #{executionTimeMs},
         #{phaseReached},
         #{phaseTimings, typeHandler=com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler},
         #{ipAddress}, #{createdAt})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertLog(CommandAuditLog log);

    @Select("SELECT * FROM ab_command_audit_log WHERE tenant_id = #{tenantId} AND command_code = #{commandCode} ORDER BY created_at DESC LIMIT #{limit}")
    List<CommandAuditLog> findByCommandCode(@Param("tenantId") Long tenantId, @Param("commandCode") String commandCode, @Param("limit") int limit);

    @Select("""
        <script>
        SELECT * FROM ab_command_audit_log
        WHERE tenant_id = #{tenantId}
        <if test="commandCode != null">AND command_code = #{commandCode}</if>
        <if test="success != null">AND success = #{success}</if>
        <if test="startDate != null">AND created_at &gt;= #{startDate}::timestamptz</if>
        <if test="endDate != null">AND created_at &lt;= #{endDate}::timestamptz</if>
        ORDER BY created_at DESC
        LIMIT #{pageSize} OFFSET #{offset}
        </script>
        """)
    List<CommandAuditLog> queryLogs(
            @Param("tenantId") Long tenantId,
            @Param("commandCode") String commandCode,
            @Param("success") Boolean success,
            @Param("startDate") String startDate,
            @Param("endDate") String endDate,
            @Param("pageSize") int pageSize,
            @Param("offset") int offset);

    @Select("""
        <script>
        SELECT COUNT(*) FROM ab_command_audit_log
        WHERE tenant_id = #{tenantId}
        <if test="commandCode != null">AND command_code = #{commandCode}</if>
        <if test="success != null">AND success = #{success}</if>
        <if test="startDate != null">AND created_at &gt;= #{startDate}::timestamptz</if>
        <if test="endDate != null">AND created_at &lt;= #{endDate}::timestamptz</if>
        </script>
        """)
    long countLogs(
            @Param("tenantId") Long tenantId,
            @Param("commandCode") String commandCode,
            @Param("success") Boolean success,
            @Param("startDate") String startDate,
            @Param("endDate") String endDate);

    @Select("SELECT * FROM ab_command_audit_log WHERE tenant_id = #{tenantId} AND id = #{id}")
    CommandAuditLog findById(@Param("tenantId") Long tenantId, @Param("id") Long id);
}
