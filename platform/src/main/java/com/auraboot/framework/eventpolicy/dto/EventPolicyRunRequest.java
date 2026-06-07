package com.auraboot.framework.eventpolicy.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

/**
 * Request body for {@code POST /api/event-policy/run}.
 *
 * <p>Mirror of {@link com.auraboot.framework.decision.dto.DrtEvaluateRequest}: resolves the
 * published policy for {@code (eventType, targetType, targetKey)}, evaluates all rules, and
 * returns resolved action plans. No execution happens here.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Data
public class EventPolicyRunRequest {

    @NotBlank
    private String eventType;

    @NotBlank
    private String targetType;

    @NotBlank
    private String targetKey;

    /**
     * Context data keyed by scope name (e.g. "record", "event").
     * Each value is a map of field → value for that scope.
     * Mirrors the context parameter of {@link com.auraboot.framework.eventpolicy.service.EventPolicyRuntimeService#run}.
     */
    private Map<String, Map<String, Object>> context;
}
