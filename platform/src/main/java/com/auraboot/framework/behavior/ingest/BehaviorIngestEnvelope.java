package com.auraboot.framework.behavior.ingest;

import com.auraboot.framework.behavior.dto.BehaviorEventInput;

import java.util.List;

/**
 * Wire payload published to {@code aura.behavior.events.v1}: a batch of client events that
 * already passed endpoint validation, with the server-resolved {@code tenantId} (and
 * {@code userId} for the authenticated path; null for the anonymous keyed path). The
 * consumer trusts these resolved identities — it has no request context to re-derive them.
 */
public record BehaviorIngestEnvelope(Long tenantId, Long userId, List<BehaviorEventInput> events) {
}
