package com.auraboot.framework.behavior.ingest;

import com.auraboot.framework.behavior.dto.BehaviorEventInput;

/**
 * Wire payload published to {@code aura.behavior.quarantine.v1}: a single event that could
 * not be durably stored (malformed or constraint-violating), with a machine-readable
 * {@code reason} and human {@code detail}. The original {@code event} is retained for replay.
 */
public record BehaviorQuarantineEnvelope(Long tenantId, Long userId, String reason, String detail,
                                         BehaviorEventInput event) {
}
