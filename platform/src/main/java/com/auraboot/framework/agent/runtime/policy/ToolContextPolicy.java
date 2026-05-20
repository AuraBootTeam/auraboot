package com.auraboot.framework.agent.runtime.policy;

import com.auraboot.framework.agent.runtime.context.AgentContextBlock;
import com.auraboot.framework.agent.runtime.context.AgentContextProvenance;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ToolContextPolicy {

    private static final Set<String> RECORD_ID_KEYS = Set.of(
            "recordId",
            "recordPid",
            "record_id",
            "record_pid",
            "pid",
            "customerId",
            "customerPid");

    record ContextDecision(boolean allowed, String reasonCode, String userSafeMessage) {

        static ContextDecision allow() {
            return new ContextDecision(true, "allowed", null);
        }

        static ContextDecision deny(String reasonCode, String userSafeMessage) {
            return new ContextDecision(false, reasonCode, userSafeMessage);
        }
    }

    ContextDecision evaluate(ToolMetadata metadata,
                             Map<String, Object> normalizedArgs,
                             List<AgentContextBlock> contextBlocks,
                             ToolPolicyActor actor) {
        if (metadata == null || !isWriteOrExternal(metadata)) {
            return ContextDecision.allow();
        }
        List<AgentContextBlock> blocks = contextBlocks == null ? List.of() : contextBlocks;
        ContextScope scope = scopeFrom(blocks);
        if (actor != null && actor.tenantId() != null
                && scope.tenantIds().size() == 1
                && !scope.tenantIds().contains(actor.tenantId())) {
            return ContextDecision.deny("context_tenant_mismatch",
                    "This action is outside the current tenant context.");
        }
        String requestedRecordId = requestedRecordId(normalizedArgs);
        if (requestedRecordId == null || scope.recordIds().isEmpty()) {
            return ContextDecision.allow();
        }
        if (!scope.recordIds().contains(requestedRecordId)) {
            return ContextDecision.deny("context_scope_violation",
                    "This action targets a record outside the current context.");
        }
        return ContextDecision.allow();
    }

    private boolean isWriteOrExternal(ToolMetadata metadata) {
        ToolEffectType effectType = metadata.getEffectType() != null
                ? metadata.getEffectType()
                : ToolEffectType.NONE;
        return effectType == ToolEffectType.INTERNAL_WRITE
                || effectType == ToolEffectType.EXTERNAL_ACTION;
    }

    private ContextScope scopeFrom(List<AgentContextBlock> blocks) {
        Set<String> recordIds = new LinkedHashSet<>();
        Set<Long> tenantIds = new LinkedHashSet<>();
        for (AgentContextBlock block : blocks) {
            if (block == null || block.provenance() == null) {
                continue;
            }
            AgentContextProvenance provenance = block.provenance();
            if (provenance.tenantId() != null) {
                tenantIds.add(provenance.tenantId());
            }
            if (!provenance.readWriteRelevant()) {
                continue;
            }
            recordIds.addAll(provenance.recordIds());
        }
        return new ContextScope(Set.copyOf(recordIds), Set.copyOf(tenantIds));
    }

    private String requestedRecordId(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return null;
        }
        for (String key : RECORD_ID_KEYS) {
            Object value = args.get(key);
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).trim();
            if (!text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private record ContextScope(Set<String> recordIds, Set<Long> tenantIds) {
    }
}
