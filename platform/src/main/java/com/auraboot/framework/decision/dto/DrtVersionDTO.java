package com.auraboot.framework.decision.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/**
 * DTO for a decision version (response shape).
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Data
public class DrtVersionDTO {
    private Long id;
    private String pid;
    private Long tenantId;
    private String decisionCode;
    private Integer version;
    private String versionTag;
    private String status;
    private String kind;
    private String runtimeAdapter;
    private String contentFormat;
    private JsonNode contentJson;
    private JsonNode inputSchemaJson;
    private JsonNode outputSchemaJson;
    private JsonNode contextSchemaJson;
    private List<String> fieldRefs;
    private List<String> functionRefs;
    private String contentHash;
    private Instant effectiveFrom;
    private Instant effectiveTo;
    private String publishedBy;
    private Instant publishedAt;
    private Instant createdAt;
}
