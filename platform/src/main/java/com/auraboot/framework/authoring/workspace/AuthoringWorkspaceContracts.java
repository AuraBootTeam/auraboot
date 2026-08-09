package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.BoundaryDecision;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.CapabilityManifest;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.PatchOperation;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** HTTP-safe contracts for the contextual-authoring workspace. */
public final class AuthoringWorkspaceContracts {

    private AuthoringWorkspaceContracts() {
    }

    public record CapabilityRegistryView(
            String checksum,
            List<CapabilityManifest> manifests) {
    }

    public record OpenSessionRequest(
            @NotBlank String pagePid,
            JsonNode interactionContext) {
    }

    public record ApplyPatchRequest(
            @Positive long expectedRevision,
            @NotBlank String blockId,
            @NotBlank String propertyPath,
            @NotNull PatchOperation operation,
            JsonNode value,
            @NotBlank String manifestChecksum) {
    }

    public record MoveBlockRequest(
            @Positive long expectedRevision,
            @NotBlank @Size(max = 120) String blockId,
            @Size(min = 1, max = 120) String beforeBlockId,
            @NotBlank String manifestChecksum) {
    }

    public record SessionView(
            String sessionPid,
            String changeSetPid,
            String pagePid,
            String state,
            long revision,
            String riskLevel,
            String route,
            String publishPolicy,
            String validationState,
            String approvalState,
            String publishState,
            String manifestChecksum,
            JsonNode snapshot,
            JsonNode interactionContext,
            Instant expiresAt) {
    }

    public enum StudioIntent {
        PAGE_STRUCTURE,
        NEW_PAGE,
        MENU_STRUCTURE,
        DATA_MODEL,
        PERMISSION,
        WORKFLOW,
        INTEGRATION
    }

    public record CreateHandoffRequest(
            @Positive long expectedRevision,
            @NotNull StudioIntent intent,
            @Size(max = 120) String blockId,
            @Size(max = 240) String propertyPath) {
    }

    public record HandoffCreatedView(
            String contextId,
            String targetRoute,
            Instant expiresAt) {
    }

    public record HandoffContextView(
            String pagePid,
            String changeSetPid,
            String sessionPid,
            long revision,
            StudioIntent intent,
            String targetRoute,
            String returnTo,
            String blockId,
            String propertyPath,
            JsonNode interactionContext,
            Instant expiresAt) {
    }

    public record PatchResult(
            SessionView session,
            String changeItemPid,
            BoundaryDecision decision,
            JsonNode previousValue,
            JsonNode savedValue) {
    }

    public record RevisionRequest(@Positive long expectedRevision) {
    }

    public record ReviewRequest(
            @Positive long expectedRevision,
            @Size(max = 1000) String reason) {
    }

    public record RollbackRequest(
            @Positive long expectedChannelVersion,
            @NotBlank @Size(max = 1000) String reason) {
    }

    public record ChangeSetView(
            String changeSetPid,
            String pagePid,
            long ownerUserId,
            String status,
            long revision,
            String riskLevel,
            String route,
            String publishPolicy,
            String validationState,
            String approvalState,
            String publishState,
            String manifestChecksum) {
    }

    public record ReleaseView(
            String releasePid,
            String changeSetPid,
            long changeSetRevision,
            String previousReleasePid,
            String status,
            String manifestChecksum,
            long channelVersion,
            Instant activatedAt) {
    }
}
