package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Binding Rule DTO
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Data
public class BindingRuleDTO {

    private Long id;
    private String pid;
    private Long commandId;
    private String ruleType;
    private String expression;
    private String targetModel;
    private String targetField;
    private String sourceField;
    private String handlerClass;
    private String eventType;
    private String config;
    private Integer sequence;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
