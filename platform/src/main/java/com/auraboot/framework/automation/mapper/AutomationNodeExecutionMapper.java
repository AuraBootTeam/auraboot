package com.auraboot.framework.automation.mapper;

import com.auraboot.framework.automation.entity.AutomationNodeExecution;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Mapper for {@link AutomationNodeExecution}.
 *
 * <p>All read queries are scoped by {@code tenant_id} as the very first predicate so
 * row-level isolation cannot be bypassed by a missing service-layer filter (red line
 * §13 / MetaContext tenant). Callers MUST resolve the tenant from
 * {@link com.auraboot.framework.application.tenant.MetaContext} before invoking.
 */
@Mapper
public interface AutomationNodeExecutionMapper extends BaseMapper<AutomationNodeExecution> {

    @Select("""
        SELECT * FROM ab_automation_node_execution
        WHERE tenant_id = #{tenantId}
          AND automation_log_id = #{logId}
        ORDER BY started_at ASC NULLS LAST, id ASC
        """)
    List<AutomationNodeExecution> findByLogIdAndTenant(
            @Param("tenantId") Long tenantId,
            @Param("logId") Long logId);

    @Select("""
        SELECT * FROM ab_automation_node_execution
        WHERE tenant_id = #{tenantId}
          AND process_instance_id = #{processInstanceId}
        ORDER BY started_at ASC NULLS LAST, id ASC
        """)
    List<AutomationNodeExecution> findByProcessInstanceIdAndTenant(
            @Param("tenantId") Long tenantId,
            @Param("processInstanceId") String processInstanceId);
}
