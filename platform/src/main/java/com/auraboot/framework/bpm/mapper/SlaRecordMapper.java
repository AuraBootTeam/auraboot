package com.auraboot.framework.bpm.mapper;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.auraboot.framework.bpm.entity.SlaRecordEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.Arrays;
import java.util.List;

/**
 * SLA record mapper.
 *
 * Note: warning_history is a JSONB column requiring autoResultMap for proper type handler resolution.
 * Use default methods with selectList()/selectOne() instead of @Select for tenant-scoped queries.
 */
@Mapper
public interface SlaRecordMapper extends BaseMapper<SlaRecordEntity> {

    default SlaRecordEntity findByPid(String pid, Long tenantId) {
        return selectOne(new QueryWrapper<SlaRecordEntity>()
                .eq("pid", pid)
                .eq("tenant_id", tenantId));
    }

    /**
     * Fetch all active SLA records across all tenants for the scheduler.
     * Uses @InterceptorIgnore to bypass TenantLineInterceptor — this is intentional
     * because the scheduler must process records from every tenant.
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM ab_sla_record WHERE status IN ('running', 'warning', 'paused') ORDER BY deadline_time ASC")
    List<SlaRecordEntity> findActiveRecords();

    default List<SlaRecordEntity> findByProcessInstance(String processInstanceId, Long tenantId) {
        return selectList(new QueryWrapper<SlaRecordEntity>()
                .eq("process_instance_id", processInstanceId)
                .eq("tenant_id", tenantId)
                .orderByDesc("created_at"));
    }

    default List<SlaRecordEntity> findActiveByProcessInstance(String processInstanceId, Long tenantId) {
        return selectList(new QueryWrapper<SlaRecordEntity>()
                .eq("process_instance_id", processInstanceId)
                .eq("tenant_id", tenantId)
                .in("status", Arrays.asList("running", "warning", "paused"))
                .orderByDesc("created_at"));
    }

    default List<SlaRecordEntity> findByTaskId(String taskId, Long tenantId) {
        return selectList(new QueryWrapper<SlaRecordEntity>()
                .eq("task_id", taskId)
                .eq("tenant_id", tenantId)
                .orderByDesc("created_at"));
    }

    default List<SlaRecordEntity> findActiveBytenant(Long tenantId) {
        return selectList(new QueryWrapper<SlaRecordEntity>()
                .eq("tenant_id", tenantId)
                .in("status", Arrays.asList("running", "warning", "overdue"))
                .orderByAsc("deadline_time"));
    }

    /**
     * Find SLA records by tenant with optional status filter.
     * Used by monitor drill-down API.
     */
    default List<SlaRecordEntity> findByTenantFiltered(Long tenantId, String status) {
        QueryWrapper<SlaRecordEntity> qw = new QueryWrapper<SlaRecordEntity>()
                .eq("tenant_id", tenantId);
        if (status != null && !"all".equals(status)) {
            qw.eq("status", status);
        }
        qw.orderByAsc("deadline_time");
        return selectList(qw);
    }
}
