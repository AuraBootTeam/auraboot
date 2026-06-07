package com.auraboot.framework.decision.dto;

import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.decision.model.VersionBinding;
import jakarta.validation.constraints.NotBlank;
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
    private String callerType;
    private String callerRef;
    private String correlationId;

    /**
     * Context data keyed by {@link Scope} name (case-insensitive).
     * Each value is a map of field → value for that scope.
     */
    private Map<String, Map<String, Object>> context;
}
