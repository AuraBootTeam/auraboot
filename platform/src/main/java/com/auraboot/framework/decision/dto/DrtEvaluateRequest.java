package com.auraboot.framework.decision.dto;

import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.decision.model.VersionBinding;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * Request body for {@code POST /api/decision/evaluate}.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Data
public class DrtEvaluateRequest {

    @NotBlank
    @Size(max = 100)
    private String decisionCode;

    /**
     * Version binding strategy: LATEST (default), FIXED_VERSION, FIXED_TAG.
     * May be null — defaults to LATEST.
     */
    private VersionBinding binding;

    /** Specific version number (required when binding = FIXED_VERSION / DEPLOYMENT_VERSION). */
    private Integer fixedVersion;

    /** Version tag (required when binding = VERSION_TAG). */
    private String versionTag;

    /** Point in time (required when binding = EFFECTIVE_TIME / AS_OF_EVENT_TIME). */
    private java.time.Instant asOf;

    /** Caller identity for audit log */
    @Size(max = 50)
    private String callerType;
    @Size(max = 200)
    private String callerRef;
    @Size(max = 64)
    private String correlationId;

    /** Stable key for deterministic rollout bucketing. */
    @Size(max = 200)
    private String routingKey;

    /** Optional segment/cohort label used by rollout eligibility rules. */
    @Size(max = 100)
    private String tenantSegment;

    /**
     * Context data keyed by {@link Scope} name (case-insensitive).
     * Each value is a map of field → value for that scope.
     */
    private Map<String, Map<String, Object>> context;
}
