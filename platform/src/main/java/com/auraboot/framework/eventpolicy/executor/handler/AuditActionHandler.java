package com.auraboot.framework.eventpolicy.executor.handler;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.eventpolicy.entity.DrtActionAuditEntity;
import com.auraboot.framework.eventpolicy.executor.ActionHandler;
import com.auraboot.framework.eventpolicy.mapper.DrtActionAuditMapper;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * First production {@link ActionHandler} (docs/2.md §7): the {@code WRITE_AUDIT} action — a
 * policy-author-configured business audit entry written when a rule matches. Self-contained
 * (writes {@code ab_drt_action_audit}; no external service), so it both delivers a real action type
 * and proves the SPI end-to-end with a registered Spring bean. External-service handlers
 * (NOTIFY / START_PROCESS / CREATE_TASK …) follow this same shape.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditActionHandler implements ActionHandler {

    private final DrtActionAuditMapper auditMapper;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(String actionType) {
        return "WRITE_AUDIT".equals(actionType);
    }

    @Override
    public void execute(ResolvedActionPlan plan, DecisionContext context) {
        Long tid = MetaContext.exists() ? MetaContext.getCurrentTenantId() : null;
        if (tid == null) {
            throw new IllegalStateException("Tenant context required for WRITE_AUDIT action");
        }
        Map<String, Object> payload = plan.payload() != null ? plan.payload() : Map.of();
        DrtActionAuditEntity row = new DrtActionAuditEntity();
        row.setPid(UniqueIdGenerator.generate());
        row.setTenantId(tid);
        row.setRuleCode(plan.ruleCode());
        row.setActionType(plan.type());
        row.setTarget(plan.target());
        Object message = payload.get("message");
        row.setMessage(message != null ? String.valueOf(message) : null);
        row.setPayloadJson(objectMapper.valueToTree(payload));
        row.setIdempotencyKey(plan.idempotencyKey());
        row.setCreatedAt(Instant.now());
        auditMapper.insert(row);
    }
}
