package com.auraboot.framework.behavior.dto;

import java.util.List;

public record BehaviorQuarantineReplayBatchResult(
        int total,
        int replayed,
        int duplicate,
        int failed,
        List<BehaviorQuarantineReplayResult> results
) {
}
