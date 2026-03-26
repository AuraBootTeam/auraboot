package com.auraboot.framework.consistency.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Response DTO for consistency rule.
 */
@Data
public class ConsistencyRuleResponse {

    private Long id;
    private String pid;
    private String code;
    private String name;
    private String ruleType;
    private String severity;
    private String sourceModel;
    private String sourceField;
    private String targetModel;
    private String targetField;
    private String linkField;
    private String aggregation;
    private String operator;
    private String messageTemplate;
    private Boolean enabled;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;
}
