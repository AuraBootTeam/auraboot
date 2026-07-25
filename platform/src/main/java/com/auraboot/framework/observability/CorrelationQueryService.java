package com.auraboot.framework.observability;

import com.auraboot.framework.agent.trace.entity.GenAiUsageRecord;
import com.auraboot.framework.agent.trace.mapper.GenAiUsageMapper;
import com.auraboot.framework.application.security.AdminAuditService;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.audit.entity.AdminEventLog;
import com.auraboot.framework.audit.mapper.AdminEventLogMapper;
import com.auraboot.framework.behavior.entity.BehaviorEvent;
import com.auraboot.framework.behavior.mapper.BehaviorEventMapper;
import com.auraboot.framework.meta.dto.CommandAuditLogDTO;
import com.auraboot.framework.meta.mapper.CommandAuditLogMapper;
import com.auraboot.framework.observability.dto.CorrelationView;
import com.auraboot.framework.observability.dto.PermissionDenialView;
import com.auraboot.framework.permission.mapper.PermissionAuditLogMapper;
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

    /**
     * One request can produce many denials (a list page checks per row). Bounded so a
     * pathological trace cannot turn the console into an unbounded read.
     */
    private static final int PERMISSION_DENIAL_LIMIT = 200;

    /** One request writes at most a couple of admin-audit rows; bounded for the same reason. */
    private static final int ADMIN_ACTION_LIMIT = 50;

    private final CommandAuditLogMapper commandAuditLogMapper;
    private final GenAiUsageMapper genAiUsageMapper;
    private final BehaviorEventMapper behaviorEventMapper;
    private final AdminEventLogMapper adminEventLogMapper;
    private final PermissionAuditLogMapper permissionAuditLogMapper;
    private final AdminAuditService adminAuditService;

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
        // findByOtelTraceId, not findByTraceId — the latter searches the Rule Center
        // ruleTraceId inside evaluation_trace, which is a different identifier and would
        // silently return nothing here.
        view.setPermissionDenials(
                permissionAuditLogMapper.findByOtelTraceId(tenantId, traceId, PERMISSION_DENIAL_LIMIT)
                        .stream()
                        .map(PermissionDenialView::from)
                        .toList());
        view.setAdminActions(
                adminAuditService.findByTraceId(tenantId, traceId, ADMIN_ACTION_LIMIT));
        return view;
    }
}
