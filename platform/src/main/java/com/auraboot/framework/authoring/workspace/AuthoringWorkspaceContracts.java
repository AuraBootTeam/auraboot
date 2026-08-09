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
import java.util.Map;

/** HTTP-safe contracts for the contextual-authoring workspace. */
public final class AuthoringWorkspaceContracts {

    private AuthoringWorkspaceContracts() {
    }

    public record CapabilityRegistryView(
            String checksum,
            List<CapabilityManifest> manifests) {
    }

    public record ReviewWorkspaceView(
            SessionView session,
            CapabilityRegistryView capabilities) {
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

    public record ObserveChangeSetRequest(
            JsonNode interactionContext) {
    }

    public record TakeoverWriterLeaseRequest(
            @Positive long expectedRevision,
            @NotBlank @Size(max = 1000) String reason) {
    }

    public record WriterLeaseView(
            String status,
            long revision,
            Instant leasedUntil) {
    }

    public record ValidationIssueView(
            String code,
            String severity,
            String changeItemPid,
            String blockId,
            String propertyPath,
            String messageKey) {
    }

    public record ValidationSummaryView(
            String validationRunPid,
            long revision,
            String status,
            int errorCount,
            List<ValidationIssueView> issues,
            Instant validatedAt) {
    }

    public record ImpactDependencyView(
            String resourceType,
            String resourceCode,
            String resourcePid,
            int version,
            int rowVersion) {
    }

    public record ImpactSummaryView(
            String impactRunPid,
            long revision,
            String status,
            String dependencyChecksum,
            List<ImpactDependencyView> dependencies,
            String failureCode,
            Instant analyzedAt) {
    }

    public record SessionView(
            String sessionPid,
            String changeSetPid,
            String pagePid,
            long ownerUserId,
            String changeSetStatus,
            String workspaceMode,
            String state,
            long revision,
            String riskLevel,
            String route,
            String publishPolicy,
            String validationState,
            ValidationSummaryView validation,
            String impactState,
            ImpactSummaryView impact,
            String approvalState,
            String publishState,
            String manifestChecksum,
            JsonNode snapshot,
            JsonNode interactionContext,
            WriterLeaseView writerLease,
            Instant expiresAt) {
    }

    /** Minimal role identity exposed to the governed structure-preview selector. */
    public record RolePreviewTargetView(
            String rolePid,
            String roleCode,
            String roleName) {
    }

    /** One structural visibility/write decision; it never contains record data. */
    public record RoleStructureDecisionView(
            String nodeType,
            String nodeId,
            String label,
            String permissionCode,
            boolean allowed,
            boolean visible,
            boolean writable,
            String reason) {
    }

    /**
     * Governed role structure preview. The booleans are deliberate contract assertions consumed by
     * the Studio UI and acceptance tests; this response never impersonates the target role.
     */
    public record RoleStructurePreviewView(
            String mode,
            String pagePid,
            RolePreviewTargetView targetRole,
            boolean actorIntersectionApplied,
            boolean businessDataIncluded,
            boolean exportAllowed,
            boolean businessActionsAllowed,
            List<RoleStructureDecisionView> decisions) {
    }

    /** One deterministic widget projection generated without querying tenant business data. */
    public record SyntheticPreviewWidgetView(
            String source,
            String value,
            List<Map<String, Object>> series) {
    }

    /**
     * In-memory synthetic fixture for Studio preview. Values are generated from schema metadata,
     * are never loaded from tenant records, and are never persisted.
     */
    public record SyntheticPreviewView(
            String mode,
            String pagePid,
            String source,
            boolean isolatedFromTenantData,
            boolean persisted,
            boolean exportAllowed,
            boolean businessActionsAllowed,
            long fixtureRevision,
            Map<String, Object> formValues,
            List<Map<String, Object>> records,
            Map<String, SyntheticPreviewWidgetView> widgets) {
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

    public record ResumeEditingRequest(
            @Positive long expectedRevision,
            @NotBlank @Size(max = 1000) String reason) {
    }

    public record SplitChangeSetRequest(
            @Positive long expectedRevision,
            @NotNull @Size(min = 1, max = 200) List<@NotBlank String> itemPids,
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 1000) String reason) {
    }

    public record ChangeItemView(
            String changeItemPid,
            String sourceChangeItemPid,
            String blockId,
            String propertyPath,
            String operation,
            String riskLevel,
            String route,
            String publishPolicy,
            String reversibility,
            long actorUserId,
            JsonNode dependencySnapshot,
            Instant createdAt) {
    }

    public record SplitChangeSetView(
            SessionView sourceSession,
            SessionView targetSession,
            List<ChangeItemView> sourceItems,
            List<ChangeItemView> targetItems,
            JsonNode lineage) {
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
            String impactState,
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

    public record RollbackEligibilityView(
            boolean eligible,
            String reasonCode,
            String targetReleasePid,
            long reversibleItemCount,
            long compensatableItemCount,
            long forwardOnlyItemCount) {
    }

    public record ReleaseHistoryItemView(
            String releasePid,
            String changeSetPid,
            long changeSetRevision,
            String previousReleasePid,
            String status,
            String reversibility,
            String manifestChecksum,
            Instant createdAt,
            Instant activatedAt) {
    }

    public record ReleaseHistoryView(
            String resourcePid,
            String activeReleasePid,
            String previousReleasePid,
            long channelVersion,
            RollbackEligibilityView rollbackEligibility,
            List<ReleaseHistoryItemView> items,
            int page,
            int size,
            long total) {
    }
}
