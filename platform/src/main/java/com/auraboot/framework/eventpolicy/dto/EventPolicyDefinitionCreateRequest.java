package com.auraboot.framework.eventpolicy.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request body for {@code POST /api/event-policy/definitions}.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Data
public class EventPolicyDefinitionCreateRequest {

    @NotBlank
    private String policyCode;

    @NotBlank
    private String policyName;

    @NotBlank
    private String eventType;

    @NotBlank
    private String targetType;

    @NotBlank
    private String targetKey;
}
