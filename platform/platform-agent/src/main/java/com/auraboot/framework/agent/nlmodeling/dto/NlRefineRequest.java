package com.auraboot.framework.agent.nlmodeling.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Request DTO for conversational refinement of generated DSL.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NlRefineRequest {

    /** Session ID for conversational continuity */
    private String sessionId;

    /** Refinement instruction (e.g., "add a status field with draft/active/archived options") */
    private String instruction;

    /** Current resources to refine */
    private NlModelingResponse.Resources currentResources;
}
