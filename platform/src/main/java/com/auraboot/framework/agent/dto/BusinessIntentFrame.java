package com.auraboot.framework.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * ACP D1 Grounding output: Business Intent Frame (BIF).
 * This is the IR (Intermediate Representation) of the ACP compiler pipeline.
 * It decouples language understanding from execution — LLM never directly operates tools,
 * only through this structured semantic representation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BusinessIntentFrame {

    // core semantics
    private String intent;              // query | analyze | create | update | delete | transition | ...
    private String object;              // primary model_code
    private List<String> objects;       // multi-object scenarios
    private String primaryObject;

    // scope and filters
    private Map<String, Object> scope;  // timeRange, orgScope, ownerScope, recordIds
    private List<Map<String, Object>> filters;           // field-level conditions
    private List<Map<String, Object>> semanticConstraints; // P1: metric/segment

    // context
    private Map<String, Object> context; // pageModel, recordId, conversationId

    // skill routing
    private List<String> candidateSkills;
    private String candidateSkillsMode;  // hint | bounded | fixed

    // confidence and matching
    private ConfidenceScore confidence;
    private String matchType;           // exact | alias | rule | embedding

    // risk and governance
    private String riskLevel;           // L0-L4
    private String actionability;       // read_only | propose | execute

    // explanation (for HITL trust)
    private Map<String, String> explanation;

    // v1.1 Active Memory pre-recall (memory-lifecycle.md §4). Snippets retrieved
    // from ab_agent_memory before emitting the BIF — gives downstream LLM access
    // to user preferences / prior decisions without extra round-trips.
    private List<Map<String, Object>> preContext;
}
