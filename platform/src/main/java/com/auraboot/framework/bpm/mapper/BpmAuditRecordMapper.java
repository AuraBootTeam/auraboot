package com.auraboot.framework.bpm.mapper;

import com.auraboot.framework.bpm.entity.BpmAuditRecordEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * BPM audit record mapper.
 * Provides database access for ab_bpm_audit_record table.
 *
 * @author AuraBoot Team
 */
@Mapper
public interface BpmAuditRecordMapper extends BaseMapper<BpmAuditRecordEntity> {

    /**
     * Find audit records by process instance ID, ordered by created_at descending.
     *
     * @param processInstanceId the process instance ID
     * @return list of audit records
     */
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
    @Select("SELECT * FROM ab_bpm_audit_record " +
            "WHERE process_definition_key = #{processDefinitionKey} " +
            "ORDER BY created_at DESC LIMIT 500")
    List<BpmAuditRecordEntity> findByProcessDefinitionKey(@Param("processDefinitionKey") String processDefinitionKey);
}
