package com.auraboot.framework.authoring.policy;

import java.util.Map;
import java.util.Set;

/**
 * Machine-readable contracts shared by contextual authoring policy, persistence and clients.
 * The server registry is authoritative; client-supplied effect/risk metadata is never trusted.
 */
public final class AuthoringPolicyContracts {

    private AuthoringPolicyContracts() {
    }

    public enum Route {
        PERSONALIZE,
        INLINE,
        GUIDED_INLINE,
        HANDOFF_STUDIO,
        DENY
    }

    public enum RiskLevel {
        L0,
        L1,
        L2,
        L3
    }

    public enum PublishPolicy {
        DIRECT_ALLOWED,
        DEFAULT_REVIEW,
        REQUIRED_REVIEW,
        STUDIO_APPROVAL,
        DENIED
    }

    public enum PatchOperation {
        ADD,
        REPLACE,
        REMOVE,
        MOVE,
        COPY
    }

    public enum EffectTag {
        PRESENTATION,
        NAVIGATION,
        VISIBILITY,
        DEFAULT_FILTER,
        DATA_BINDING,
        BUSINESS_ACTION,
        SECURITY,
        PERMISSION,
        MODEL,
        PROCESS,
        INTEGRATION
    }

    public enum ResourceScope {
        USER_PRIVATE,
        CURRENT_PAGE,
        SHARED_PAGE,
        MULTI_RESOURCE,
        NEW_RESOURCE
    }

    public enum SecurityImpact {
        NONE,
        PRESENT,
        UNKNOWN
    }

    public enum Reversibility {
        REVERSIBLE,
        COMPENSATABLE,
        FORWARD_ONLY
    }

    public enum Reason {
        CAPABILITY_ALLOWED,
        CAPABILITY_UNKNOWN,
        OPERATION_NOT_ALLOWED,
        MANIFEST_STALE,
        IMPACT_UNKNOWN,
        SHARED_RESOURCE,
        CROSS_RESOURCE,
        SECURITY_SENSITIVE,
        BUSINESS_SEMANTIC,
        PROTECTED_SEMANTIC_INVALID,
        FORWARD_ONLY
    }

    public record PropertyCapability(
            String propertyPath,
            Set<PatchOperation> allowedOperations,
            Route route,
            RiskLevel risk,
            Set<EffectTag> effectTags,
            Reversibility reversibility,
            boolean protectedSemantic,
            boolean rolePreviewRequired) {

        public PropertyCapability {
            if (propertyPath == null || propertyPath.isBlank() || !propertyPath.startsWith("/")) {
                throw new IllegalArgumentException("propertyPath must be a non-blank JSON pointer");
            }
            allowedOperations = Set.copyOf(allowedOperations);
            effectTags = Set.copyOf(effectTags);
        }
    }

    public record CapabilityManifest(
            String blockType,
            String pluginCode,
            String pluginVersion,
            String manifestVersion,
            String checksum,
            Map<String, PropertyCapability> properties) {

        public CapabilityManifest {
            properties = Map.copyOf(properties);
        }
    }

    /**
     * Trusted server-side context assembled from the resource, actor and proposed patch.
     */
    public record BoundaryEvaluationInput(
            String blockType,
            String propertyPath,
            PatchOperation operation,
            ResourceScope resourceScope,
            SecurityImpact securityImpact,
            boolean impactKnown,
            boolean protectedSemanticValid,
            String manifestChecksum) {
    }

    public record BoundaryDecision(
            Route route,
            RiskLevel risk,
            PublishPolicy publishPolicy,
            Reason reason,
            String manifestChecksum,
            boolean rolePreviewRequired) {
    }
}
