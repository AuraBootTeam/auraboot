package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.BoundaryDecision;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.CapabilityManifest;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.PatchOperation;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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

    /**
     * Creates a governed NEW_RESOURCE workspace. The request carries resource identity, its
     * published business-model binding, and navigation metadata; page structure is authored
     * afterwards in Studio.
     */
    public record CreateNewPageWorkspaceRequest(
            @Positive long expectedSourceRevision,
            @NotBlank @Size(min = 2, max = 100)
            @jakarta.validation.constraints.Pattern(
                    regexp = "^[a-zA-Z][a-zA-Z0-9_-]*$")
            String pageKey,
            @NotBlank @Size(min = 2, max = 100) String name,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 1000) String description,
            @NotBlank @jakarta.validation.constraints.Pattern(
                    regexp = "^(list|form|detail)$")
            String kind,
            @NotBlank @Size(max = 100)
            @jakarta.validation.constraints.Pattern(
                    regexp = "^[a-zA-Z][a-zA-Z0-9_.-]*$")
            String modelCode,
            @NotBlank @Size(max = 100) String parentMenuCode,
            @NotBlank @Size(max = 100)
            @jakarta.validation.constraints.Pattern(
                    regexp = "^[a-zA-Z][a-zA-Z0-9_.-]*$")
            String menuCode,
            @NotBlank @Size(max = 100) String menuName,
            @NotBlank @Size(max = 500)
            @jakarta.validation.constraints.Pattern(regexp = "^/[a-zA-Z0-9/_-]*$")
            String menuPath,
            @Size(max = 100) String menuIcon,
            @NotBlank @Size(max = 100) String permissionCode) {
    }

    public record NewPageOption(String value, String label) {
    }

    public record NewPageWorkspaceOptions(
            List<NewPageOption> models,
            List<NewPageOption> parentMenus,
            List<NewPageOption> permissions) {
    }

    public record ApplyPatchRequest(
            @Positive long expectedRevision,
            @NotBlank String blockId,
            @NotBlank String propertyPath,
            @NotNull PatchOperation operation,
            JsonNode value,
            @NotBlank String manifestChecksum) {
    }

    /** One typed property patch proposed by AI; structural creation is intentionally excluded. */
    public record AiPatchProposalItemRequest(
            @NotBlank @Size(max = 120) String blockId,
            @NotBlank @Size(max = 240) String propertyPath,
            @NotNull PatchOperation operation,
            JsonNode value,
            @NotBlank @Size(max = 64) String manifestChecksum) {
    }

    public record CreateAiPatchProposalRequest(
            @Positive long expectedRevision,
            @NotEmpty @Size(max = 50)
            List<@Valid AiPatchProposalItemRequest> items) {
    }

    public record ApplyAiPatchProposalRequest(@Positive long expectedRevision) {
    }

    public record RejectAiPatchProposalRequest(
            @NotBlank @Size(max = 1000) String reason) {
    }

    public record AiPatchProposalItemView(
            int ordinal,
            String blockId,
            String propertyPath,
            PatchOperation operation,
            JsonNode previousValue,
            JsonNode value,
            String manifestChecksum,
            BoundaryDecision decision) {
    }

    /** Server-validated proposal metadata; prompt and raw model output are never persisted. */
    public record AiPatchProposalView(
            String proposalPid,
            String sourceSessionPid,
            String changeSetPid,
            String pagePid,
            long baseRevision,
            String registryChecksum,
            String proposalHash,
            String status,
            String aggregateRisk,
            String aggregateRoute,
            String publishPolicy,
            boolean typedPatchOnly,
            boolean requiresHumanApproval,
            List<AiPatchProposalItemView> items,
            Long resultRevision,
            Instant createdAt,
            Instant appliedAt,
            Instant rejectedAt) {
    }

    public record ApplyAiPatchProposalResult(
            AiPatchProposalView proposal,
            SessionView session) {
    }

    public record MoveBlockRequest(
            @Positive long expectedRevision,
            @NotBlank @Size(max = 120) String blockId,
            @Size(min = 1, max = 120) String beforeBlockId,
            @NotBlank String manifestChecksum) {
    }

    public record CreateBlockRequest(
            @Positive long expectedRevision,
            @NotBlank @Size(max = 120) String blockId,
            @NotBlank @Size(max = 120) String blockType,
            @Size(min = 1, max = 120) String parentBlockId,
            @Size(min = 1, max = 120) String beforeBlockId,
            @NotBlank String manifestChecksum) {
    }

    public record RemoveBlockRequest(
            @Positive long expectedRevision,
            @NotBlank @Size(max = 120) String blockId,
            @NotBlank String manifestChecksum) {
    }

    public record RelocateBlockRequest(
            @Positive long expectedRevision,
            @NotBlank @Size(max = 120) String blockId,
            @NotBlank @Size(max = 120) String targetParentBlockId,
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

    /** Effective authoring layer and immutable inherited-source lineage. */
    public record OwnershipView(
            String ownershipScope,
            String sourceOwnershipScope,
            String sourcePagePid,
            String overridePid,
            String origin,
            boolean tenantOverride,
            boolean sourceMutable,
            String restoreTarget) {
    }

    public record SessionView(
            String sessionPid,
            String changeSetPid,
            String pagePid,
            OwnershipView ownership,
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

    /** Security-admin request for a bounded, actor-bound, read-only simulation session. */
    public record StartIdentitySimulationRequest(
            @NotBlank String rolePid,
            @Min(1) @Max(15) int durationMinutes,
            @NotBlank @Size(max = 1000) String reason) {
    }

    /** Audited identity simulation lifecycle and its current actor∩role structure. */
    public record IdentitySimulationView(
            String simulationPid,
            String mode,
            String sourceSessionPid,
            String pagePid,
            RolePreviewTargetView targetRole,
            boolean actorIntersectionApplied,
            boolean businessDataIncluded,
            boolean readOnly,
            boolean exportAllowed,
            boolean businessActionsAllowed,
            String status,
            Instant startedAt,
            Instant expiresAt,
            Instant endedAt,
            List<RoleStructureDecisionView> decisions) {
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
