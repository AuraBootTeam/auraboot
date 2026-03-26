package com.auraboot.framework.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Request DTO for creating or updating a NotificationRule.
 *
 * @since 5.2.0
 */
@Data
public class NotificationRuleRequest {

    @NotBlank
    private String code;

    @NotBlank
    private String name;

    private String description;

    private Boolean enabled = true;

    /** EVENT or SCHEDULED */
    @NotNull
    private String triggerType;

    /** JSON — trigger configuration object */
    private String triggerConfig;

    /** Model code to query for condition evaluation */
    private String conditionModelCode;

    /** JSON filter array [{fieldName, operator, value}] */
    private String conditionFilter;

    /** IN_APP / EMAIL / WEBHOOK */
    private String actionChannel;

    private String actionTemplateCode;

    /** OPERATOR / RECORD_OWNER / SPECIFIC_USERS */
    private String recipientType;

    private String recipientField;
}
