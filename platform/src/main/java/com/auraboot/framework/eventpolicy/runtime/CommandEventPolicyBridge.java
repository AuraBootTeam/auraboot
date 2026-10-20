package com.auraboot.framework.eventpolicy.runtime;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.eventpolicy.mapper.DrtPolicyDefinitionMapper;
import com.auraboot.framework.eventpolicy.service.OutboxService;
import com.auraboot.module.meta.event.CommandCompletedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Connects committed model commands to the existing durable event-policy outbox. */
@Component
@RequiredArgsConstructor
public class CommandEventPolicyBridge {
    public static final String EVENT_TYPE = "COMMAND_COMPLETED";
    private final DrtPolicyDefinitionMapper definitions;
    private final OutboxService outbox;
    private final ObjectMapper mapper;

    @EventListener
    public void enqueue(CommandCompletedEvent event) {
        if (event.getRecordId() == null || event.getModelCode() == null) return;
        if (!MetaContext.exists() || !Objects.equals(event.getTenantId(), MetaContext.getCurrentTenantId())) {
            throw new IllegalStateException("Command event tenant differs from its execution identity");
        }
        if (definitions.findByEventAndTarget(event.getTenantId(), EVENT_TYPE, "MODEL", event.getModelCode()).isEmpty()) return;
        Map<String, Object> before = map(event.getMetadata().get("beforeSnapshot"));
        Map<String, Object> after = new LinkedHashMap<>(before);
        after.putAll(event.getPayload());
        Map<String, Object> transition = map(event.getMetadata().get("commandStateTransition"));
        if (transition.get("stateField") instanceof String field && transition.containsKey("toState")) {
            after.put(field, transition.get("toState"));
        }
        if (!before.isEmpty() && before.equals(after)) return;
        Map<String, Object> actor = new LinkedHashMap<>();
        actor.put("userId", MetaContext.getCurrentUserId());
        actor.put("userPid", MetaContext.getCurrentUserPid());
        actor.put("username", MetaContext.getCurrentUsername());
        Map<String, Map<String, Object>> context = Map.of(
                "record", Map.of("entityCode", event.getModelCode(), "recordPid", event.getRecordId(), "data", after),
                "before", Map.of("data", before), "after", Map.of("data", after),
                "event", Map.of("eventId", event.getEventId(), "type", EVENT_TYPE, "commandCode", event.getCommandCode()),
                "actor", actor, "meta", Map.of("actionDepth",
                        event.getMetadata().get("policyActionDepth") instanceof Number number ? number.intValue() : 0));
        outbox.enqueue(event.getEventId(), EVENT_TYPE, "MODEL", event.getModelCode(), mapper.valueToTree(context));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void dispatch(CommandCompletedEvent event) {
        // An exact event lookup is a no-op when no published model policy was enqueued.
        if (event.getRecordId() != null && MetaContext.exists()
                && Objects.equals(event.getTenantId(), MetaContext.getCurrentTenantId())) {
            outbox.processEvent(event.getEventId());
        }
    }

    private static Map<String, Object> map(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }
}
