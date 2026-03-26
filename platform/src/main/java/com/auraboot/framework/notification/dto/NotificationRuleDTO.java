package com.auraboot.framework.notification.dto;

import lombok.Data;

import java.time.Instant;

/**
 * DTO for NotificationRule — used for API responses.
 *
 * @since 5.2.0
 */
@Data
public class NotificationRuleDTO {

    private Long id;
    private String code;
    private String name;
    private String description;
    private Boolean enabled;

    /** EVENT or SCHEDULED */
    private String triggerType;

    /** JSON string — trigger configuration */
    private String triggerConfig;

    /** Model code that the condition runs against */
    private String conditionModelCode;

    /** JSON filter array [{fieldName, operator, value}] */
    private String conditionFilter;

    /** IN_APP / EMAIL / WEBHOOK */
    private String actionChannel;
    private String actionTemplateCode;

    /** OPERATOR / RECORD_OWNER / SPECIFIC_USERS */
    private String recipientType;
    private String recipientField;

    private Instant lastEvaluatedAt;
    private Integer sendCount;
    private Instant createdAt;
    private Instant updatedAt;
}
