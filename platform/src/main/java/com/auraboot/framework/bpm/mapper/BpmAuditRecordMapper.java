package com.auraboot.framework.bpm.mapper;

import com.auraboot.framework.bpm.entity.BpmAuditRecordEntity;
import com.auraboot.framework.plugin.typehandler.PluginSettingsTypeHandler;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * BPM audit record mapper.
 * Provides database access for ab_bpm_audit_record table.
 *
 * <p>Note: MyBatis Plus {@code autoResultMap=true} on the entity only applies to
 * BaseMapper auto-generated methods. Hand-written {@code @Select} statements must
 * declare {@code @Results} explicitly to bind the JSONB {@link PluginSettingsTypeHandler};
 * otherwise the {@code details} column is deserialized with the default handler and
 * the resulting {@code Map<String, Object>} field becomes {@code null}.
 *
 * @author AuraBoot Team
 */
@Mapper
public interface BpmAuditRecordMapper extends BaseMapper<BpmAuditRecordEntity> {

    /**
     * Shared result mapping that binds the JSONB {@code details} column to
     * {@link PluginSettingsTypeHandler}. Reused by every {@code @Select} method
     * so the {@code Map<String, Object>} payload is materialized correctly.
     */
    String RESULT_MAP_ID = "bpmAuditRecordResult";

    /**
     * Find audit records by process instance ID, ordered by created_at descending.
     *
     * @param processInstanceId the process instance ID
     * @return list of audit records
     */
    @Results(id = RESULT_MAP_ID, value = {
            @Result(property = "id", column = "id", id = true),
            @Result(property = "pid", column = "pid"),
            @Result(property = "tenantId", column = "tenant_id"),
            @Result(property = "userId", column = "user_id"),
            @Result(property = "operation", column = "operation"),
            @Result(property = "processInstanceId", column = "process_instance_id"),
            @Result(property = "taskId", column = "task_id"),
            @Result(property = "processDefinitionKey", column = "process_definition_key"),
            @Result(property = "version", column = "version"),
            @Result(property = "details", column = "details", typeHandler = PluginSettingsTypeHandler.class),
            @Result(property = "ipAddress", column = "ip_address"),
            @Result(property = "result", column = "result"),
            @Result(property = "errorMessage", column = "error_message"),
            @Result(property = "createdAt", column = "created_at")
    })
    @Select("SELECT * FROM ab_bpm_audit_record " +
            "WHERE process_instance_id = #{processInstanceId} " +
            "ORDER BY created_at DESC")
    List<BpmAuditRecordEntity> findByProcessInstance(@Param("processInstanceId") String processInstanceId);

    /**
     * Find audit records by task ID, ordered by created_at descending.
     *
     * @param taskId the task ID
     * @return list of audit records
     */
    @ResultMap(RESULT_MAP_ID)
    @Select("SELECT * FROM ab_bpm_audit_record " +
            "WHERE task_id = #{taskId} " +
            "ORDER BY created_at DESC")
    List<BpmAuditRecordEntity> findByTaskId(@Param("taskId") String taskId);

    /**
     * Find audit records by process definition key, ordered by created_at descending.
     * Used for AI analysis of historical process execution patterns.
     *
     * @param processDefinitionKey the process definition key
     * @return list of audit records (limited to 500 most recent)
     */
    @ResultMap(RESULT_MAP_ID)
    @Select("SELECT * FROM ab_bpm_audit_record " +
            "WHERE process_definition_key = #{processDefinitionKey} " +
            "ORDER BY created_at DESC LIMIT 500")
    List<BpmAuditRecordEntity> findByProcessDefinitionKey(@Param("processDefinitionKey") String processDefinitionKey);
}
