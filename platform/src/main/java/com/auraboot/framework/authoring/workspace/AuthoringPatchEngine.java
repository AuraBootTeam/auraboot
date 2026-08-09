package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.authoring.policy.AuthoringBoundaryPolicyService;
import com.auraboot.framework.authoring.policy.AuthoringCapabilityRegistry;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.BoundaryDecision;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.BoundaryEvaluationInput;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.CapabilityManifest;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.EffectTag;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.PatchOperation;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.PropertyCapability;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.ResourceScope;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.Route;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.SecurityImpact;
import com.auraboot.framework.authoring.workspace.AuthoringSnapshotTargetResolver.DraftTarget;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;

/** Coordinates trusted policy evaluation before applying a patch to an isolated draft copy. */
@Component
public class AuthoringPatchEngine {

    private final AuthoringCapabilityRegistry registry;
    private final AuthoringBoundaryPolicyService boundaryPolicyService;
    private final AuthoringContentSanitizer contentSanitizer;
    private final AuthoringProtectedSemanticValidator semanticValidator;
    private final AuthoringSnapshotTargetResolver targetResolver;
    private final AuthoringJsonObjectPatchApplier patchApplier;

    public AuthoringPatchEngine(
            AuthoringCapabilityRegistry registry,
            AuthoringBoundaryPolicyService boundaryPolicyService,
            AuthoringContentSanitizer contentSanitizer,
            AuthoringProtectedSemanticValidator semanticValidator,
            AuthoringSnapshotTargetResolver targetResolver,
            AuthoringJsonObjectPatchApplier patchApplier) {
        this.registry = registry;
        this.boundaryPolicyService = boundaryPolicyService;
        this.contentSanitizer = contentSanitizer;
        this.semanticValidator = semanticValidator;
        this.targetResolver = targetResolver;
        this.patchApplier = patchApplier;
    }

    public PreparedPatch prepareInline(
            JsonNode sourceSnapshot,
            String blockId,
            String propertyPath,
            PatchOperation operation,
            JsonNode proposedValue,
            String manifestChecksum,
            ResourceScope resourceScope) {
        return prepare(sourceSnapshot, blockId, propertyPath, operation, proposedValue,
                manifestChecksum, resourceScope, false);
    }

    public PreparedPatch prepareStudio(
            JsonNode sourceSnapshot,
            String blockId,
            String propertyPath,
            PatchOperation operation,
            JsonNode proposedValue,
            String manifestChecksum,
            ResourceScope resourceScope) {
        return prepare(sourceSnapshot, blockId, propertyPath, operation, proposedValue,
                manifestChecksum, resourceScope, true);
    }

    private PreparedPatch prepare(
            JsonNode sourceSnapshot,
            String blockId,
            String propertyPath,
            PatchOperation operation,
            JsonNode proposedValue,
            String manifestChecksum,
            ResourceScope resourceScope,
            boolean studioRoute) {
        DraftTarget target = targetResolver.resolve(sourceSnapshot, blockId);
        CapabilityManifest manifest = registry.find(target.blockType()).orElse(null);
        PropertyCapability capability = manifest == null
                ? null
                : manifest.properties().get(propertyPath);
        BoundaryDecision decision = boundaryPolicyService.evaluate(new BoundaryEvaluationInput(
                target.blockType(),
                propertyPath,
                operation,
                resourceScope,
                securityImpact(capability),
                capability != null,
                protectedSemanticValid(capability, target.block(), propertyPath, proposedValue),
                manifestChecksum));
        requireAllowedDecision(decision, studioRoute);

        JsonNode savedValue = operation == PatchOperation.REMOVE
                ? null
                : contentSanitizer.sanitize(propertyPath, proposedValue);
        JsonNode previousValue = patchApplier.apply(
                target.block(), propertyPath, operation, savedValue);
        return new PreparedPatch(target.snapshot(), target.blockType(), capability, decision,
                previousValue, savedValue == null ? null : savedValue.deepCopy());
    }

    private SecurityImpact securityImpact(PropertyCapability capability) {
        if (capability != null
                && (capability.effectTags().contains(EffectTag.SECURITY)
                    || capability.effectTags().contains(EffectTag.PERMISSION))) {
            return SecurityImpact.PRESENT;
        }
        return SecurityImpact.NONE;
    }

    private boolean protectedSemanticValid(
            PropertyCapability capability,
            ObjectNode block,
            String propertyPath,
            JsonNode proposedValue) {
        return capability != null
                && (!capability.protectedSemantic()
                    || semanticValidator.isValid(block, propertyPath, proposedValue));
    }

    private void requireAllowedDecision(BoundaryDecision decision, boolean studioRoute) {
        if (decision.route() == Route.DENY) {
            throw invalid("authoring.policy." + decision.reason().name().toLowerCase(Locale.ROOT));
        }
        if (decision.route() == Route.HANDOFF_STUDIO && !studioRoute) {
            throw new ResponseStatusException(CONFLICT,
                    "authoring.handoff." + decision.reason().name().toLowerCase(Locale.ROOT));
        }
    }

    private ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(UNPROCESSABLE_ENTITY, reason);
    }

    public record PreparedPatch(
            ObjectNode snapshot,
            String blockType,
            PropertyCapability capability,
            BoundaryDecision decision,
            JsonNode previousValue,
            JsonNode savedValue) {
    }
}
