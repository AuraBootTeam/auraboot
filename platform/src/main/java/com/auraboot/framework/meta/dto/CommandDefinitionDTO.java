package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Command Definition DTO
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Data
public class CommandDefinitionDTO {

    private Long id;
    private String pid;
    private Long tenantId;
    private String code;
    private String displayName;
    private String description;
    private String modelCode;
    private String type;
    private String inputSchema;
    private String targetModels;
    private String executionConfig;
    private Integer version;
    private String semver;
    private Boolean isCurrent;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private List<BindingRuleDTO> bindingRules;
}
