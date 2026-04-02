package com.auraboot.framework.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Skill-level semantic input — sourced from Business Intent Frame, not raw Tool parameters.
 * This ensures D1 → Skill decoupling: Skills consume business semantics, not LLM output.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillInput {
    private String intent;                          // from BIF
    private String object;                          // primary model_code
    private Map<String, Object> scope;              // timeRange, orgScope, ownerScope
    private List<Map<String, Object>> filters;      // field-level filter conditions
    private Map<String, Object> context;            // page context, session
    private Map<String, Object> parameters;         // Skill-specific params (user-provided or LLM-generated)
    private String userMessage;                     // Natural language message for orchestration mode (LLM user prompt)
}
