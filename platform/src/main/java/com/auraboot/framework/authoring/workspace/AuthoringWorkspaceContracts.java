package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.BoundaryDecision;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.CapabilityManifest;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.PatchOperation;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

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

    public record PatchResult(
            SessionView session,
            String changeItemPid,
            BoundaryDecision decision,
            JsonNode previousValue,
            JsonNode savedValue) {
    }
}
