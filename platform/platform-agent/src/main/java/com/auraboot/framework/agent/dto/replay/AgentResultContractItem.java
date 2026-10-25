package com.auraboot.framework.agent.dto.replay;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

/**
 * Stable replay anchor for a ResultContract associated with an Action row.
 *
 * <p>The live SSE ResultContract stream is transport state. Replay derives a
 * deterministic contract from {@code ab_agent_action} so operators can jump
 * from an action audit row to the external output contract shape used by the
 * chat UI.
 */
@Data
@Builder
public class AgentResultContractItem {

    private String contractId;
    private String actionPid;
    private String source;
    private Map<String, Object> contract;
    private Instant emittedAt;
}
