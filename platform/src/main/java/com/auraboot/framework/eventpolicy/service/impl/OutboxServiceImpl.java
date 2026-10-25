package com.auraboot.framework.eventpolicy.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.event.context.ThreadContextScopes;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.eventpolicy.entity.DrtOutboxEntity;
import com.auraboot.framework.eventpolicy.mapper.DrtOutboxMapper;
import com.auraboot.framework.eventpolicy.service.EventPolicyRuntimeService;
import com.auraboot.framework.eventpolicy.service.OutboxService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Default {@link OutboxService}. enqueue writes a PENDING row in the caller's transaction (so the
 * event is durable iff the save commits); processPending runs each event's policy via
 * {@link EventPolicyRuntimeService#runAndExecute} and records the outcome. §8: failures are recorded
 * (FAILED + last_error), not swallowed; one event's failure does not stop the batch.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxServiceImpl implements OutboxService {

    private final DrtOutboxMapper outboxMapper;
    private final EventPolicyRuntimeService runtimeService;
    private final ObjectMapper objectMapper;
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;
    private final com.auraboot.framework.tenant.dao.mapper.TenantMemberMapper tenantMembers;

    private final ThreadContextScopes contextScopes;

    @Transactional
    @Override
    public void enqueue(String eventId, String eventType, String targetType, String targetKey, JsonNode context) {
        Long tid = requireTenant();
        if (outboxMapper.findByEventId(tid, eventId) != null) {
            return; // idempotent enqueue
        }
        DrtOutboxEntity row = new DrtOutboxEntity();
        row.setPid(UniqueIdGenerator.generate());
        row.setTenantId(tid);
        row.setEventId(eventId);
        row.setEventType(eventType);
        row.setTargetType(targetType);
        row.setTargetKey(targetKey);
        row.setContextJson(context);
        row.setStatus("PENDING");
        row.setAttempts(0);
        row.setCreatedAt(Instant.now());
        outboxMapper.insert(row);
    }

    @Override
    public int processPending(int limit) {
        Long tid = requireTenant();
        List<DrtOutboxEntity> pending = outboxMapper.findPending(tid, limit);
        // The exact-event method is also the after-commit dispatch entry. Runtime action
        // transactions and durable outcomes are independent of a worker's ambient transaction.
        for (DrtOutboxEntity row : pending) processEvent(row.getEventId());
        return pending.size();
    }

    @Override
    public void processEvent(String eventId) {
        Long tenantId = requireTenant();
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.executeWithoutResult(status -> {
            DrtOutboxEntity row = outboxMapper.findByEventIdForUpdate(tenantId, eventId);
            if (row != null && "PENDING".equals(row.getStatus())) processRow(row);
        });
    }

    private void processRow(DrtOutboxEntity row) {
        try {
            Map<String, Map<String, Object>> ctx = row.getContextJson() == null
                    ? Map.of() : objectMapper.convertValue(row.getContextJson(),
                            new TypeReference<Map<String, Map<String, Object>>>() {});
            var actor = ctx.getOrDefault("actor", Map.of());
            Long actorId = actor.get("userId") instanceof Number number ? number.longValue() : null;
            var member = actorId == null ? null : tenantMembers.findByTenantIdAndUserId(row.getTenantId(), actorId);
            if (actorId != null && (member == null || !row.getTenantId().equals(member.getTenantId())
                    || !actorId.equals(member.getUserId()) || Boolean.TRUE.equals(member.getDeletedFlag())
                    || !"active".equalsIgnoreCase(member.getStatus()))) {
                throw new IllegalStateException("The event actor is not an active member of this space");
            }
            MetaContext.Snapshot identity = new MetaContext.Snapshot(row.getTenantId(), actorId,
                    actor.get("userPid") instanceof String pid ? pid : null,
                    actor.get("username") instanceof String name ? name : null,
                    java.util.Set.of(), member == null ? null : member.getId(), null, null, null);
            contextScopes.runCleared(() -> MetaContext.runWithSnapshot(identity, () -> {
                var result = runtimeService.runAndExecute(row.getEventType(), row.getTargetType(), row.getTargetKey(), ctx);
                var status = result.execution().overallStatus();
                boolean failed = status == com.auraboot.framework.eventpolicy.executor.PolicyExecutionResult.OverallStatus.FAILED
                        || status == com.auraboot.framework.eventpolicy.executor.PolicyExecutionResult.OverallStatus.PARTIAL_SUCCESS
                        || result.policy().status() == com.auraboot.framework.eventpolicy.model.EventPolicyResult.Status.ERROR
                        || result.policy().status() == com.auraboot.framework.eventpolicy.model.EventPolicyResult.Status.CONFLICT;
                row.setStatus(failed ? "FAILED" : "PROCESSED");
                row.setLastError(failed ? "Event policy execution requires attention" : null);
            }));
            row.setProcessedAt(Instant.now());
        } catch (RuntimeException exception) {
            row.setStatus("FAILED");
            row.setLastError(exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
            log.warn("Outbox event {} failed: {}", row.getEventId(), row.getLastError());
        }
        row.setAttempts((row.getAttempts() == null ? 0 : row.getAttempts()) + 1);
        outboxMapper.updateById(row);
    }

    private Long requireTenant() {
        Long tid = MetaContext.exists() ? MetaContext.getCurrentTenantId() : null;
        if (tid == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Tenant context required for outbox");
        }
        return tid;
    }
}
