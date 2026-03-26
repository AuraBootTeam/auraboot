package com.auraboot.framework.meta.dto;

import lombok.Data;

/**
 * Request DTO for updating an existing SoD rule.
 *
 * @author AuraBoot Team
 * @since 6.2.0
 */
@Data
public class SodRuleUpdateRequest {

    private String ruleName;
    private String description;
    private String commandA;
    private String commandB;
    private String entityScope;
    private String enforcement;
    private Boolean enabled;
}
