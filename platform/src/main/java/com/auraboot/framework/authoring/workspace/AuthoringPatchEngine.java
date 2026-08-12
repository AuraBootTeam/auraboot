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

import static com.auraboot.framework.authoring.policy.CoreAuthoringCapabilityRegistry.REORDER_WITHIN_PARENT_PATH;
import static com.auraboot.framework.authoring.policy.CoreAuthoringCapabilityRegistry.CREATE_BLOCK_PATH;
import static com.auraboot.framework.authoring.policy.CoreAuthoringCapabilityRegistry.RELOCATE_BLOCK_PATH;
import static com.auraboot.framework.authoring.policy.CoreAuthoringCapabilityRegistry.REMOVE_BLOCK_PATH;
import static com.auraboot.framework.authoring.policy.CoreAuthoringCapabilityRegistry.PAGE_KIND_PATH;
import static com.auraboot.framework.authoring.policy.CoreAuthoringCapabilityRegistry.PAGE_MANIFEST_BLOCK_TYPE;
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
    private final AuthoringStableBlockTreeEditor blockTreeEditor;

    public AuthoringPatchEngine(
            AuthoringCapabilityRegistry registry,
            AuthoringBoundaryPolicyService boundaryPolicyService,
            AuthoringContentSanitizer contentSanitizer,
            AuthoringProtectedSemanticValidator semanticValidator,
            AuthoringSnapshotTargetResolver targetResolver,
            AuthoringJsonObjectPatchApplier patchApplier,
            AuthoringStableBlockTreeEditor blockTreeEditor) {
        this.registry = registry;
        this.boundaryPolicyService = boundaryPolicyService;
        this.contentSanitizer = contentSanitizer;
        this.semanticValidator = semanticValidator;
        this.targetResolver = targetResolver;
        this.patchApplier = patchApplier;
        this.blockTreeEditor = blockTreeEditor;
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

    public PreparedPatch prepareStudioMove(
            JsonNode sourceSnapshot,
            String blockId,
            String beforeBlockId,
            String manifestChecksum,
            ResourceScope resourceScope) {
        DraftTarget target = targetResolver.resolve(sourceSnapshot, blockId);
        CapabilityManifest manifest = registry.find(target.blockType()).orElse(null);
        PropertyCapability capability = manifest == null
                ? null
                : manifest.properties().get(REORDER_WITHIN_PARENT_PATH);
        BoundaryDecision decision = boundaryPolicyService.evaluate(new BoundaryEvaluationInput(
                target.blockType(),
                REORDER_WITHIN_PARENT_PATH,
                PatchOperation.MOVE,
                resourceScope,
                securityImpact(capability),
                capability != null,
                capability != null && !capability.protectedSemantic(),
                manifestChecksum));
        requireAllowedDecision(decision, true);

        AuthoringStableBlockTreeEditor.MoveResult move = blockTreeEditor.moveBefore(
                target.snapshot(), blockId, beforeBlockId);
        return new PreparedPatch(
                move.snapshot(),
                target.blockType(),
                capability,
                decision,
                move.previousValue(),
                move.savedValue());
    }

    public PreparedPatch prepareStudioCreate(
            JsonNode sourceSnapshot,
            String blockId,
            String blockType,
            String parentBlockId,
            String beforeBlockId,
            String manifestChecksum,
            ResourceScope resourceScope) {
        CapabilityManifest manifest = registry.find(blockType).orElse(null);
        PropertyCapability capability = manifest == null
                ? null
                : manifest.properties().get(CREATE_BLOCK_PATH);
        BoundaryDecision decision = structureDecision(
                blockType, CREATE_BLOCK_PATH, PatchOperation.ADD,
                capability, manifestChecksum, resourceScope);
        requireAllowedDecision(decision, true);
        AuthoringStableBlockTreeEditor.StructureResult result = blockTreeEditor.createBlock(
                sourceSnapshot, blockId, blockType, parentBlockId, beforeBlockId);
        return preparedStructure(result, capability, decision);
    }

    public PreparedPatch prepareStudioRemove(
            JsonNode sourceSnapshot,
            String blockId,
            String manifestChecksum,
            ResourceScope resourceScope) {
        DraftTarget target = targetResolver.resolve(sourceSnapshot, blockId);
        CapabilityManifest manifest = registry.find(target.blockType()).orElse(null);
        PropertyCapability capability = manifest == null
                ? null
                : manifest.properties().get(REMOVE_BLOCK_PATH);
        BoundaryDecision decision = structureDecision(
                target.blockType(), REMOVE_BLOCK_PATH, PatchOperation.REMOVE,
                capability, manifestChecksum, resourceScope);
        requireAllowedDecision(decision, true);
        return preparedStructure(
                blockTreeEditor.removeBlock(target.snapshot(), blockId), capability, decision);
    }

    public PreparedPatch prepareStudioRelocate(
            JsonNode sourceSnapshot,
            String blockId,
            String targetParentBlockId,
            String beforeBlockId,
            String manifestChecksum,
            ResourceScope resourceScope) {
        DraftTarget target = targetResolver.resolve(sourceSnapshot, blockId);
        CapabilityManifest manifest = registry.find(target.blockType()).orElse(null);
        PropertyCapability capability = manifest == null
                ? null
                : manifest.properties().get(RELOCATE_BLOCK_PATH);
        BoundaryDecision decision = structureDecision(
                target.blockType(), RELOCATE_BLOCK_PATH, PatchOperation.MOVE,
                capability, manifestChecksum, resourceScope);
        requireAllowedDecision(decision, true);
        return preparedStructure(
                blockTreeEditor.relocateBlock(
                        target.snapshot(), blockId, targetParentBlockId, beforeBlockId),
                capability,
                decision);
    }

    public PreparedPatch prepareStudioPageKindSwitch(
            JsonNode sourceSnapshot,
            String targetKind,
            String manifestChecksum,
            ResourceScope resourceScope) {
        CapabilityManifest manifest = registry.find(PAGE_MANIFEST_BLOCK_TYPE).orElse(null);
        PropertyCapability capability = manifest == null
                ? null
                : manifest.properties().get(PAGE_KIND_PATH);
        BoundaryDecision decision = structureDecision(
                PAGE_MANIFEST_BLOCK_TYPE, PAGE_KIND_PATH, PatchOperation.REPLACE,
                capability, manifestChecksum, resourceScope);
        requireAllowedDecision(decision, true);
        return preparedStructure(
                blockTreeEditor.switchPageKind(sourceSnapshot, targetKind), capability, decision);
    }

    private BoundaryDecision structureDecision(
            String blockType,
            String path,
            PatchOperation operation,
            PropertyCapability capability,
            String manifestChecksum,
            ResourceScope resourceScope) {
        return boundaryPolicyService.evaluate(new BoundaryEvaluationInput(
                blockType,
                path,
                operation,
                resourceScope,
                securityImpact(capability),
                capability != null,
                capability != null,
                manifestChecksum));
    }

    private PreparedPatch preparedStructure(
            AuthoringStableBlockTreeEditor.StructureResult result,
            PropertyCapability capability,
            BoundaryDecision decision) {
        return new PreparedPatch(
                result.snapshot(),
                result.blockType(),
                capability,
                decision,
                result.previousValue(),
                result.savedValue());
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
                protectedSemanticValid(
                        capability, sourceSnapshot, target.block(), propertyPath, proposedValue),
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
            JsonNode sourceSnapshot,
            ObjectNode block,
            String propertyPath,
            JsonNode proposedValue) {
        return capability != null
                && (!capability.protectedSemantic()
                    || semanticValidator.isValid(
                            sourceSnapshot, block, propertyPath, proposedValue));
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
