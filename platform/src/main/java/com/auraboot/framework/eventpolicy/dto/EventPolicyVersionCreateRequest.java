package com.auraboot.framework.eventpolicy.dto;

import com.auraboot.framework.eventpolicy.model.ConflictStrategy;
import com.auraboot.framework.eventpolicy.model.DedupStrategy;
import com.auraboot.framework.eventpolicy.model.ExecutionMode;
import com.auraboot.framework.eventpolicy.model.FailureStrategy;
import com.auraboot.framework.eventpolicy.model.MatchMode;
import com.auraboot.framework.eventpolicy.model.PolicyPhase;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Request body for {@code POST /api/event-policy/definitions/{code}/versions}.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Data
public class EventPolicyVersionCreateRequest {

    @NotNull
    private PolicyPhase phase;

    @NotNull
    private MatchMode matchMode;

    private ExecutionMode executionMode;

    private FailureStrategy failureStrategy;

    private ConflictStrategy conflictStrategy;

    private DedupStrategy dedupStrategy;

    /** Serialised {@code List<PolicyRule>} — each rule has ConditionNode + List<PolicyAction>. */
    @NotNull
    private JsonNode rulesJson;
}
