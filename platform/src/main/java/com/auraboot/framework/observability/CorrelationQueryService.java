package com.auraboot.framework.observability;

import com.auraboot.framework.agent.trace.entity.GenAiUsageRecord;
import com.auraboot.framework.agent.trace.mapper.GenAiUsageMapper;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.audit.entity.AdminEventLog;
import com.auraboot.framework.audit.mapper.AdminEventLogMapper;
import com.auraboot.framework.behavior.entity.BehaviorEvent;
import com.auraboot.framework.behavior.mapper.BehaviorEventMapper;
import com.auraboot.framework.meta.dto.CommandAuditLogDTO;
import com.auraboot.framework.meta.mapper.CommandAuditLogMapper;
import com.auraboot.framework.observability.dto.CorrelationView;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Assembles the unified eagle-eye {@link CorrelationView} for one trace id by joining
 * the command / cost / behavior / audit domains on {@code trace_id} (the OTel trace id
 * stamped across all of them). Tenant-scoped (explicit + platform tenant interceptor).
 */
@Service
@RequiredArgsConstructor
public class CorrelationQueryService {

    private final CommandAuditLogMapper commandAuditLogMapper;
    private final GenAiUsageMapper genAiUsageMapper;
    private final BehaviorEventMapper behaviorEventMapper;
    private final AdminEventLogMapper adminEventLogMapper;

    public CorrelationView byTrace(String traceId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        CorrelationView view = new CorrelationView();
        view.setTraceId(traceId);
        view.setCommandAudits(commandAuditLogMapper.findByTraceId(tenantId, traceId).stream()
                .map(CommandAuditLogDTO::from)
                .toList());
        view.setLlmUsage(genAiUsageMapper.selectList(new LambdaQueryWrapper<GenAiUsageRecord>()
                .eq(GenAiUsageRecord::getTenantId, tenantId)
                .eq(GenAiUsageRecord::getTraceId, traceId)));
        view.setBehaviorEvents(behaviorEventMapper.selectList(new LambdaQueryWrapper<BehaviorEvent>()
                .eq(BehaviorEvent::getTenantId, tenantId)
                .eq(BehaviorEvent::getTraceId, traceId)));
        view.setAuditEvents(adminEventLogMapper.selectList(new LambdaQueryWrapper<AdminEventLog>()
                .eq(AdminEventLog::getTenantId, tenantId)
                .eq(AdminEventLog::getTraceId, traceId)));
        return view;
    }
}
