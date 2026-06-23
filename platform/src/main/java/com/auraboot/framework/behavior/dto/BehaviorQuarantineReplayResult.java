package com.auraboot.framework.behavior.dto;

public record BehaviorQuarantineReplayResult(
        Long quarantineId,
        String status,
        String eventId,
        Long behaviorEventId,
        String detail
) {
}
