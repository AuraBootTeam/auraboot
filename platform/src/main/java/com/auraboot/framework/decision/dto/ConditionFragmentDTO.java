package com.auraboot.framework.decision.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/**
 * API read model for a reusable condition fragment.
 */
@Data
public class ConditionFragmentDTO {
    private Long id;
    private String pid;
    private Long tenantId;
    private String fragmentCode;
    private String fragmentName;
    private String description;
    private String scopeType;
    private String scopeRef;
    private Integer version;
    private String status;
    private JsonNode conditionSpec;
    private List<String> fieldRefs;
    private List<String> decisionRefs;
    private String ownerModule;
    private Boolean enabled;
    private String publishedBy;
    private Instant publishedAt;
    private String createdBy;
    private Instant createdAt;
    private String updatedBy;
    private Instant updatedAt;
}
