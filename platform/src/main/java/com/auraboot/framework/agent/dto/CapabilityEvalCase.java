package com.auraboot.framework.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * A single evaluation test case for Agent capability testing.
 * Tests whether the Agent correctly selects tools and fills parameters.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CapabilityEvalCase {
    /** Unique case identifier */
    private String caseId;

    /** Natural language task description (what the user would say) */
    private String taskDescription;

    /** Expected tool code(s) the Agent should select */
    private List<String> expectedToolCodes;

    /** Expected key input parameters */
    private Map<String, Object> expectedInputKeys;

    /** Tools that should NOT be selected (negative test) */
    private List<String> forbiddenToolCodes;

    /** Expected risk level awareness */
    private String expectedRiskLevel;

    /** Whether the Agent should request confirmation before executing */
    private boolean expectsConfirmation;

    /** Category: TOOL_SELECTION, PARAMETER_FILL, SAFETY_BOUNDARY, MULTI_STEP */
    private String category;
}
