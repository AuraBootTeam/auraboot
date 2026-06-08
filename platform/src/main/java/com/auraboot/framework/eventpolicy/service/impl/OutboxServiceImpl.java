package com.auraboot.framework.eventpolicy.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
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
        int processed = 0;
        for (DrtOutboxEntity row : pending) {
            try {
                Map<String, Map<String, Object>> ctx = row.getContextJson() == null
                        ? Map.of()
                        : objectMapper.convertValue(row.getContextJson(),
                                new TypeReference<Map<String, Map<String, Object>>>() {});
                runtimeService.runAndExecute(row.getEventType(), row.getTargetType(), row.getTargetKey(), ctx);
                row.setStatus("PROCESSED");
                row.setProcessedAt(Instant.now());
            } catch (RuntimeException e) {
                row.setStatus("FAILED");
                row.setLastError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                log.warn("Outbox event {} failed: {}", row.getEventId(), row.getLastError());
            }
            row.setAttempts((row.getAttempts() == null ? 0 : row.getAttempts()) + 1);
            outboxMapper.updateById(row);
            processed += 1;
        }
        return processed;
    }

    private Long requireTenant() {
        Long tid = MetaContext.exists() ? MetaContext.getCurrentTenantId() : null;
        if (tid == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Tenant context required for outbox");
        }
        return tid;
    }
}
