package com.auraboot.framework.plugin.dto.imports;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DTO for importing ACP agent definitions from plugin packages.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentDefinitionDTO {

    /**
     * Agent code, unique within a tenant.
     */
    private String agentCode;

    /**
     * Display name.
     */
    private String name;

    private String description;
    private String avatarUrl;
    private String agentType;
    private String model;
    private String systemPrompt;
    private List<String> tools;
    private List<String> skills;
    private Map<String, Object> guardrails;
    private Map<String, Object> soulProfile;
    private String personality;
    private String expertise;
    private String communicationStyle;
    private String boundaries;
    private String soulGoals;
    private List<String> allowedModels;
    private List<String> allowedOperations;
    private Integer maxTools;
    private Integer maxConcurrentRuns;
    private Integer executionTimeoutSeconds;
    private Map<String, Object> eventTriggers;
    private String autoReplyMode;
    private String status;
    private String stats;
    private String visibility;

    @JsonIgnore
    private Map<String, Object> unknownFields;

    @JsonAnySetter
    public void setUnknownField(String key, Object value) {
        if (unknownFields == null) {
            unknownFields = new HashMap<>();
        }
        unknownFields.put(key, value);
    }

    public boolean isValid() {
        return agentCode != null && !agentCode.isBlank()
                && name != null && !name.isBlank();
    }

    public String getEffectiveName() {
        return name != null && !name.isBlank() ? name : agentCode;
    }
}
