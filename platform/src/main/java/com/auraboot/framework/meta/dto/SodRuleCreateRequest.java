package com.auraboot.framework.meta.dto;

import lombok.Data;

/**
 * Request DTO for creating a new SoD rule.
 *
 * @author AuraBoot Team
 * @since 6.2.0
 */
@Data
public class SodRuleCreateRequest {

    private String ruleCode;
    private String ruleName;
    private String description;
    private String commandA;
    private String commandB;

    /**
     * SAME_RECORD, SAME_MODEL, or GLOBAL.
     */
    private String entityScope = "same_record";

    /**
     * HARD (block), SOFT (warn + audit), AUDIT_ONLY (log only).
     */
    private String enforcement = "hard";

    private Boolean enabled = true;
}
