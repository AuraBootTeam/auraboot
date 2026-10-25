package com.auraboot.framework.agent.dto.replay;

import lombok.Builder;
import lombok.Data;

/**
 * Replay UI MVP — projection of the upstream {@code ab_agent_bif} grounding
 * frame for a run. The full BIF contract is rich (multiple JSONB columns,
 * candidate skills, explanation traces); the Replay UI surfaces only the
 * 5 fields that fit on the detail header without overwhelming an operator.
 *
 * <p>Returns {@code null} when the run has no BIF row (e.g. legacy runs
 * created before D1 grounding shipped, or paths that bypass grounding).
 */
@Data
@Builder
public class AgentBifSummary {

    private String pid;
    private String intent;
    private String primaryObject;
    private String confidence;        // raw JSONB returned as string
    private String dispatchedSkill;
    private String channel;
}
