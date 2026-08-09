package com.auraboot.framework.authoring.policy;

import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.BoundaryDecision;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.BoundaryEvaluationInput;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.CapabilityManifest;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.EffectTag;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.PropertyCapability;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.PublishPolicy;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.Reason;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.ResourceScope;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.Reversibility;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.RiskLevel;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.Route;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.SecurityImpact;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Set;

/**
 * Five-dimensional contextual-authoring boundary policy. Every caller receives the same stable
 * route/risk/reason decision from server-owned capability metadata.
 */
@Service
public class AuthoringBoundaryPolicyService {

    private static final Set<EffectTag> STUDIO_EFFECTS = Set.copyOf(EnumSet.of(
            EffectTag.DATA_BINDING,
            EffectTag.BUSINESS_ACTION,
            EffectTag.SECURITY,
            EffectTag.PERMISSION,
            EffectTag.MODEL,
            EffectTag.PROCESS,
            EffectTag.INTEGRATION));

    private final AuthoringCapabilityRegistry capabilityRegistry;

    public AuthoringBoundaryPolicyService(AuthoringCapabilityRegistry capabilityRegistry) {
        this.capabilityRegistry = capabilityRegistry;
    }

    public BoundaryDecision evaluate(BoundaryEvaluationInput input) {
        if (input == null || input.blockType() == null || input.blockType().isBlank()) {
            return denied(Reason.CAPABILITY_UNKNOWN, null);
        }
        CapabilityManifest manifest = capabilityRegistry.find(input.blockType()).orElse(null);
        if (manifest == null) {
            return denied(Reason.CAPABILITY_UNKNOWN, null);
        }
        PropertyCapability capability = manifest.properties().get(input.propertyPath());
        BoundaryDecision validationFailure = validationFailure(input, manifest, capability);
        if (validationFailure != null) {
            return validationFailure;
        }
        return allowedRoute(input, manifest, capability);
    }

    private BoundaryDecision validationFailure(
            BoundaryEvaluationInput input,
            CapabilityManifest manifest,
            PropertyCapability capability) {
        if (input.manifestChecksum() == null
                || !manifest.checksum().equals(input.manifestChecksum())) {
            return denied(Reason.MANIFEST_STALE, manifest.checksum());
        }
        if (capability == null) {
            return denied(Reason.CAPABILITY_UNKNOWN, manifest.checksum());
        }
        if (input.operation() == null || !capability.allowedOperations().contains(input.operation())) {
            return denied(Reason.OPERATION_NOT_ALLOWED, manifest.checksum());
        }
        if (impactUnknown(input)) {
            return denied(Reason.IMPACT_UNKNOWN, manifest.checksum());
        }
        if (capability.protectedSemantic() && !input.protectedSemanticValid()) {
            return denied(Reason.PROTECTED_SEMANTIC_INVALID, manifest.checksum());
        }
        return null;
    }

    private boolean impactUnknown(BoundaryEvaluationInput input) {
        return !input.impactKnown()
                || input.resourceScope() == null
                || input.securityImpact() == null
                || input.securityImpact() == SecurityImpact.UNKNOWN;
    }

    private BoundaryDecision allowedRoute(
            BoundaryEvaluationInput input,
            CapabilityManifest manifest,
            PropertyCapability capability) {
        if (input.resourceScope() == ResourceScope.MULTI_RESOURCE
                || input.resourceScope() == ResourceScope.NEW_RESOURCE) {
            return studio(Reason.CROSS_RESOURCE, manifest.checksum(), capability.rolePreviewRequired());
        }
        if (input.resourceScope() == ResourceScope.SHARED_PAGE) {
            return studio(Reason.SHARED_RESOURCE, manifest.checksum(), capability.rolePreviewRequired());
        }
        if (input.securityImpact() == SecurityImpact.PRESENT) {
            return studio(Reason.SECURITY_SENSITIVE, manifest.checksum(), true);
        }
        if (!java.util.Collections.disjoint(capability.effectTags(), STUDIO_EFFECTS)) {
            return studio(Reason.BUSINESS_SEMANTIC, manifest.checksum(), capability.rolePreviewRequired());
        }
        if (capability.reversibility() == Reversibility.FORWARD_ONLY) {
            return studio(Reason.FORWARD_ONLY, manifest.checksum(), capability.rolePreviewRequired());
        }
        if (input.resourceScope() == ResourceScope.USER_PRIVATE) {
            return new BoundaryDecision(Route.PERSONALIZE, capability.risk(),
                    PublishPolicy.DIRECT_ALLOWED, Reason.CAPABILITY_ALLOWED,
                    manifest.checksum(), capability.rolePreviewRequired());
        }
        return new BoundaryDecision(capability.route(), capability.risk(),
                publishPolicy(capability.risk()), Reason.CAPABILITY_ALLOWED,
                manifest.checksum(), capability.rolePreviewRequired());
    }

    private BoundaryDecision denied(Reason reason, String checksum) {
        return new BoundaryDecision(Route.DENY, RiskLevel.L3, PublishPolicy.DENIED,
                reason, checksum, false);
    }

    private BoundaryDecision studio(Reason reason, String checksum, boolean rolePreviewRequired) {
        return new BoundaryDecision(Route.HANDOFF_STUDIO, RiskLevel.L3,
                PublishPolicy.STUDIO_APPROVAL, reason, checksum, rolePreviewRequired);
    }

    private PublishPolicy publishPolicy(RiskLevel risk) {
        return switch (risk) {
            case L0 -> PublishPolicy.DIRECT_ALLOWED;
            case L1 -> PublishPolicy.DEFAULT_REVIEW;
            case L2 -> PublishPolicy.REQUIRED_REVIEW;
            case L3 -> PublishPolicy.STUDIO_APPROVAL;
        };
    }
}
