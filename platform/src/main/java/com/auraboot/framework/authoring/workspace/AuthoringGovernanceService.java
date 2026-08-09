package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.authoring.workspace.AuthoringChangeSetSplitter.ChangeItem;
import com.auraboot.framework.authoring.workspace.AuthoringChangeSetSplitter.SplitPlan;
import com.auraboot.framework.authoring.workspace.AuthoringDraftValidator.ValidationResult;
import com.auraboot.framework.authoring.workspace.AuthoringImpactAnalyzer.ImpactResult;
import com.auraboot.framework.authoring.workspace.AuthoringGovernanceRepository.ChannelRow;
import com.auraboot.framework.authoring.workspace.AuthoringGovernanceRepository.GovernanceRow;
import com.auraboot.framework.authoring.workspace.AuthoringGovernanceRepository.ReleaseChannelHistory;
import com.auraboot.framework.authoring.workspace.AuthoringGovernanceRepository.ReleaseHistoryRow;
import com.auraboot.framework.authoring.workspace.AuthoringGovernanceRepository.ReleaseHistorySnapshot;
import com.auraboot.framework.authoring.workspace.AuthoringGovernanceRepository.ReleaseRow;
import com.auraboot.framework.authoring.workspace.AuthoringGovernanceRepository.RollbackRow;
import com.auraboot.framework.authoring.workspace.AuthoringGovernanceRepository.SplitPersistenceCommand;
import com.auraboot.framework.authoring.workspace.AuthoringGovernanceRepository.SplitPersistenceResult;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ChangeItemView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ChangeSetView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ReleaseHistoryItemView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ReleaseHistoryView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ReleaseView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.RollbackEligibilityView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ReviewRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.RevisionRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.RollbackRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ResumeEditingRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.SessionView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.SplitChangeSetRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.SplitChangeSetView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceRepository.AuditEntry;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceRepository.AggregatePolicy;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceRepository.WorkspaceRow;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/** Review, publish and rollback state machine for one frozen ChangeSet revision. */
@Service
public class AuthoringGovernanceService {

    private static final Duration WRITER_LEASE = Duration.ofMinutes(5);
    private static final Duration SESSION_TTL = Duration.ofHours(8);
    private static final String REVIEW_WORKSPACE_MODE = "REVIEW";

    private final AuthoringGovernanceRepository governanceRepository;
    private final AuthoringWorkspaceRepository workspaceRepository;
    private final AuthoringGovernanceValidator governanceValidator;
    private final AuthoringPageSnapshotFactory snapshotFactory;
    private final AuthoringRuntimeSnapshotSanitizer runtimeSanitizer;
    private final AuthoringChangeSetSplitter changeSetSplitter;
    private final AuthoringAggregatePolicyService aggregatePolicyService;
    private final AuthoringDraftValidator draftValidator;
    private final AuthoringImpactAnalyzer impactAnalyzer;
    private final AuthoringWorkspaceViewMapper viewMapper;
    private final AuthoringAuditService auditService;
    private final ObjectMapper objectMapper;

    public AuthoringGovernanceService(
            AuthoringGovernanceRepository governanceRepository,
            AuthoringWorkspaceRepository workspaceRepository,
            AuthoringGovernanceValidator governanceValidator,
            AuthoringPageSnapshotFactory snapshotFactory,
            AuthoringRuntimeSnapshotSanitizer runtimeSanitizer,
            AuthoringChangeSetSplitter changeSetSplitter,
            AuthoringAggregatePolicyService aggregatePolicyService,
            AuthoringDraftValidator draftValidator,
            AuthoringImpactAnalyzer impactAnalyzer,
            AuthoringWorkspaceViewMapper viewMapper,
            AuthoringAuditService auditService,
            ObjectMapper objectMapper) {
        this.governanceRepository = governanceRepository;
        this.workspaceRepository = workspaceRepository;
        this.governanceValidator = governanceValidator;
        this.snapshotFactory = snapshotFactory;
        this.runtimeSanitizer = runtimeSanitizer;
        this.changeSetSplitter = changeSetSplitter;
        this.aggregatePolicyService = aggregatePolicyService;
        this.draftValidator = draftValidator;
        this.impactAnalyzer = impactAnalyzer;
        this.viewMapper = viewMapper;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional(noRollbackFor = AuthoringStaleStateException.class)
    public SessionView prepare(String sessionPid, RevisionRequest request) {
        Identity identity = identity();
        WorkspaceRow workspace = requireOwnedSession(identity, sessionPid);
        GovernanceRow row = requireChangeSet(identity, workspace.changeSetPid(), true);
        requireWritableSession(workspace, row, identity, request.expectedRevision());
        governanceValidator.requireRevision(row, request.expectedRevision());
        governanceValidator.requireStatus(row, "DRAFT", "REJECTED");
        governanceValidator.requireFresh(row);
        List<ChangeItem> items = governanceRepository.findActiveItems(row);
        if (items.isEmpty()) {
            throw conflict("authoring.submit.empty");
        }
        ValidationResult validation = draftValidator.validate(row.snapshot(), items);
        String validationRunPid = UniqueIdGenerator.generate();
        governanceRepository.recordValidation(
                row, validation, validationRunPid, snapshotFactory.checksum(row.snapshot()),
                identity.userId());
        ObjectNode validationMetadata = objectMapper.createObjectNode();
        validationMetadata.put("validationRunPid", validationRunPid);
        validationMetadata.put("validatedRevision", row.revision());
        validationMetadata.put("errorCount", validation.errorCount());
        audit(identity, row, sessionPid,
                validation.valid() ? "CHANGE_SET_VALIDATED" : "CHANGE_SET_VALIDATION_FAILED",
                validation.valid() ? "ALLOW" : "DENY",
                validation.valid() ? "REVISION_VALID" : "REVISION_INVALID",
                validationMetadata);
        if (!validation.valid()) {
            return sessionView(identity, sessionPid);
        }
        ImpactResult impact = impactAnalyzer.analyze(
                row.tenantId(), row.envId(), row.snapshot());
        String impactRunPid = UniqueIdGenerator.generate();
        governanceRepository.recordImpact(
                row, impact, impactRunPid, snapshotFactory.checksum(row.snapshot()),
                identity.userId());
        ObjectNode impactMetadata = objectMapper.createObjectNode();
        impactMetadata.put("impactRunPid", impactRunPid);
        impactMetadata.put("analyzedRevision", row.revision());
        impactMetadata.put("dependencyCount", impact.dependencies().size());
        audit(identity, row, sessionPid,
                impact.known() ? "CHANGE_SET_IMPACT_KNOWN" : "CHANGE_SET_IMPACT_FAILED",
                impact.known() ? "ALLOW" : "DENY",
                impact.known() ? "DEPENDENCIES_RESOLVED" : impact.failureCode(),
                impactMetadata);
        return sessionView(identity, sessionPid);
    }

    @Transactional(noRollbackFor = AuthoringStaleStateException.class)
    public ChangeSetView submit(String sessionPid, RevisionRequest request) {
        Identity identity = identity();
        WorkspaceRow workspace = requireOwnedSession(identity, sessionPid);
        GovernanceRow row = requireChangeSet(identity, workspace.changeSetPid(), true);
        requireWritableSession(workspace, row, identity, request.expectedRevision());
        governanceValidator.requireRevision(row, request.expectedRevision());
        governanceValidator.requireStatus(row, "DRAFT", "REJECTED");
        governanceValidator.requireFresh(row);
        governanceValidator.requirePrepared(row);
        boolean approvalRequired = governanceValidator.approvalRequired(row);
        governanceRepository.submit(row, approvalRequired, identity.userId());
        audit(identity, row, sessionPid, "CHANGE_SET_SUBMITTED", "ALLOW",
                approvalRequired ? "REVIEW_REQUIRED" : "DIRECT_ALLOWED", null);
        return view(requireChangeSet(identity, row.changeSetPid(), false));
    }

    @Transactional(noRollbackFor = AuthoringStaleStateException.class)
    public ChangeSetView approve(String changeSetPid, ReviewRequest request) {
        Identity identity = identity();
        GovernanceRow row = requireChangeSet(identity, changeSetPid, true);
        governanceValidator.requireRevision(row, request.expectedRevision());
        governanceValidator.requireStatus(row, "IN_REVIEW");
        governanceValidator.requireFourEyes(row, identity.userId());
        governanceValidator.requireFresh(row);
        governanceRepository.approve(row, identity.userId(), safeReason(request.reason()));
        audit(identity, row, null, "CHANGE_SET_APPROVED", "ALLOW", "REVISION_APPROVED",
                objectMapper.valueToTree(Map.of("approvedRevision", row.revision())));
        return view(requireChangeSet(identity, changeSetPid, false));
    }

    @Transactional
    public ChangeSetView withdrawReview(String sessionPid, ResumeEditingRequest request) {
        Identity identity = identity();
        WorkspaceRow workspace = requireOwnedSession(identity, sessionPid);
        GovernanceRow row = requireChangeSet(identity, workspace.changeSetPid(), true);
        governanceValidator.requireRevision(row, request.expectedRevision());
        governanceValidator.requireStatus(row, "IN_REVIEW");
        requireOwner(row, identity);
        requireResumableSession(workspace, request.expectedRevision());
        governanceRepository.withdrawReview(
                row, workspace.sessionId(), identity.userId(), Instant.now().plus(WRITER_LEASE));
        audit(identity, row, sessionPid, "CHANGE_SET_REVIEW_WITHDRAWN", "ALLOW",
                "OWNER_RESUMED_EDITING", revisionTransition(row, request.reason()));
        return view(requireChangeSet(identity, row.changeSetPid(), false));
    }

    @Transactional
    public ChangeSetView reopenApproved(String sessionPid, ResumeEditingRequest request) {
        Identity identity = identity();
        WorkspaceRow workspace = requireOwnedSession(identity, sessionPid);
        GovernanceRow row = requireChangeSet(identity, workspace.changeSetPid(), true);
        governanceValidator.requireRevision(row, request.expectedRevision());
        governanceValidator.requireStatus(row, "APPROVED");
        requireOwner(row, identity);
        requireResumableSession(workspace, request.expectedRevision());
        governanceRepository.reopenApproved(
                row, workspace.sessionId(), identity.userId(), Instant.now().plus(WRITER_LEASE));
        audit(identity, row, sessionPid, "CHANGE_SET_APPROVAL_INVALIDATED", "ALLOW",
                "OWNER_RESUMED_EDITING", revisionTransition(row, request.reason()));
        return view(requireChangeSet(identity, row.changeSetPid(), false));
    }

    @Transactional
    public ChangeSetView reject(String changeSetPid, ReviewRequest request) {
        Identity identity = identity();
        GovernanceRow row = requireChangeSet(identity, changeSetPid, true);
        governanceValidator.requireRevision(row, request.expectedRevision());
        governanceValidator.requireStatus(row, "IN_REVIEW");
        governanceValidator.requireFourEyes(row, identity.userId());
        String reason = requireReason(request.reason());
        governanceRepository.reject(
                row, identity.userId(), reason,
                Instant.now().plus(WRITER_LEASE));
        audit(identity, row, null, "CHANGE_SET_REJECTED", "ALLOW", "REVISION_REJECTED",
                revisionTransition(row, reason));
        return view(requireChangeSet(identity, changeSetPid, false));
    }

    @Transactional(readOnly = true)
    public List<ChangeItemView> listChangeItems(String sessionPid) {
        Identity identity = identity();
        WorkspaceRow workspace = requireOwnedSession(identity, sessionPid);
        requireNonReviewWorkspace(workspace);
        GovernanceRow row = requireChangeSet(identity, workspace.changeSetPid(), false);
        requireOwner(row, identity);
        return governanceRepository.findActiveItems(row).stream().map(this::itemView).toList();
    }

    @Transactional
    public SplitChangeSetView split(
            String sessionPid,
            SplitChangeSetRequest request) {
        Identity identity = identity();
        WorkspaceRow workspace = requireOwnedSession(identity, sessionPid);
        GovernanceRow row = requireChangeSet(identity, workspace.changeSetPid(), true);
        requireOwner(row, identity);
        governanceValidator.requireStatus(row, "DRAFT", "REJECTED");
        requireWritableSession(workspace, row, identity, request.expectedRevision());

        List<ChangeItem> items = governanceRepository.findActiveItems(row);
        SplitPlan plan = changeSetSplitter.split(row.snapshot(), items, request.itemPids());
        AggregatePolicy sourceAggregate = aggregatePolicyService.aggregateItems(plan.sourceItems());
        AggregatePolicy targetAggregate = aggregatePolicyService.aggregateItems(plan.targetItems());
        String reason = requireSplitText(request.reason(), "authoring.split.reason-required");
        String title = requireSplitText(request.title(), "authoring.split.title-required");
        String targetChangeSetPid = UniqueIdGenerator.generate();
        String targetSessionPid = UniqueIdGenerator.generate();
        ArrayNode lineage = splitLineage(row);
        ObjectNode dependencies = splitDependencySnapshot(plan);
        Instant now = Instant.now();

        SplitPersistenceResult persisted = governanceRepository.split(new SplitPersistenceCommand(
                row,
                plan,
                sourceAggregate,
                targetAggregate,
                workspace.sessionId(),
                identity.userId(),
                targetChangeSetPid,
                UniqueIdGenerator.generate(),
                targetSessionPid,
                UniqueIdGenerator.generate(),
                UniqueIdGenerator.generate(),
                title,
                reason,
                workspace.interactionContext(),
                lineage,
                dependencies,
                now.plus(SESSION_TTL),
                now.plus(WRITER_LEASE)));

        GovernanceRow sourceReloaded = requireChangeSet(identity, row.changeSetPid(), false);
        GovernanceRow targetReloaded = requireChangeSet(identity, targetChangeSetPid, false);
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("reason", reason);
        metadata.put("sourceRevision", row.revision());
        metadata.put("sourceResultRevision", persisted.sourceRevision());
        metadata.put("targetChangeSetPid", targetChangeSetPid);
        metadata.put("targetRevision", persisted.targetRevision());
        metadata.set("selectedItemPids", dependencies.get("targetItemPids"));
        audit(identity, sourceReloaded, sessionPid, "CHANGE_SET_SPLIT_SOURCE", "ALLOW",
                "DEPENDENCY_INDEPENDENT_PARTITION", metadata);

        ObjectNode targetMetadata = metadata.deepCopy();
        targetMetadata.put("sourceChangeSetPid", row.changeSetPid());
        audit(identity, targetReloaded, targetSessionPid, "CHANGE_SET_SPLIT_TARGET", "ALLOW",
                "LINEAGE_PRESERVED", targetMetadata);

        WorkspaceRow sourceSession = requireOwnedSession(identity, sessionPid);
        WorkspaceRow targetSession = requireOwnedSession(identity, targetSessionPid);
        return new SplitChangeSetView(
                viewMapper.toView(sourceSession, identity.userId()),
                viewMapper.toView(targetSession, identity.userId()),
                governanceRepository.findActiveItems(sourceReloaded).stream()
                        .map(this::itemView).toList(),
                governanceRepository.findActiveItems(targetReloaded).stream()
                        .map(this::itemView).toList(),
                lineage);
    }

    @Transactional(noRollbackFor = AuthoringStaleStateException.class)
    public ReleaseView publish(String changeSetPid, RevisionRequest request) {
        Identity identity = identity();
        GovernanceRow row = null;
        try {
            row = requireChangeSet(identity, changeSetPid, true);
            governanceValidator.requireRevision(row, request.expectedRevision());
            governanceValidator.requireStatus(row, "APPROVED");
            governanceValidator.requireFresh(row);
            governanceValidator.requirePublishable(row);

            ObjectNode runtimeSnapshot = runtimeSanitizer.sanitize(row.snapshot());
            ChannelRow channel = governanceRepository.lockChannel(row);
            String releasePid = UniqueIdGenerator.generate();
            ObjectNode manifest = releaseManifest(row, channel, releasePid, runtimeSnapshot);
            String manifestChecksum = snapshotFactory.checksum(manifest);
            ReleaseRow release = governanceRepository.activateRelease(
                    row, channel, releasePid, UniqueIdGenerator.generate(),
                    UniqueIdGenerator.generate(), manifest, manifestChecksum,
                    runtimeSnapshot, snapshotFactory.checksum(runtimeSnapshot), identity.userId());
            audit(identity, row, null, "RELEASE_PUBLISHED", "ALLOW", "ATOMIC_CHANNEL_SWITCH",
                    objectMapper.valueToTree(Map.of(
                            "releasePid", release.releasePid(),
                            "channelVersion", release.channelVersion())));
            return releaseView(release);
        } catch (RuntimeException failure) {
            recordPublishFailure(identity, row, changeSetPid, request, failure);
            throw failure;
        }
    }

    @Transactional(readOnly = true)
    public ReleaseHistoryView releaseHistory(String changeSetPid, int page, int size) {
        Identity identity = identity();
        GovernanceRow row = requireChangeSet(identity, changeSetPid, false);
        int offset = (page - 1) * size;
        ReleaseHistorySnapshot history = governanceRepository.findReleaseHistory(
                row, offset, size);
        ReleaseChannelHistory channel = history.channel();
        RollbackEligibilityView eligibility = rollbackEligibility(channel);
        return new ReleaseHistoryView(
                row.resourcePid(),
                channel == null ? null : channel.activeReleasePid(),
                channel == null ? null : channel.previousReleasePid(),
                channel == null ? 0 : channel.channelVersion(),
                eligibility,
                history.releases().stream().map(this::releaseHistoryItemView).toList(),
                page, size, history.total());
    }

    @Transactional
    public ReleaseView rollback(String releasePid, RollbackRequest request) {
        Identity identity = identity();
        RollbackRow rollback = governanceRepository.lockRollback(
                releasePid, identity.tenantId(), identity.envId());
        if (rollback == null) {
            throw new ResponseStatusException(NOT_FOUND, "authoring.rollback.not-found");
        }
        if (rollback.channelVersion() != request.expectedChannelVersion()) {
            throw conflict("authoring.rollback.channel-conflict");
        }
        if (governanceRepository.countNonReversibleItems(rollback) != 0) {
            throw conflict("authoring.rollback.not-reversible");
        }
        ReleaseRow release = governanceRepository.rollback(rollback, identity.userId());
        GovernanceRow prior = requireChangeSet(identity, release.changeSetPid(), false);
        audit(identity, prior, null, "RELEASE_ROLLED_BACK", "ALLOW", "REVERSIBLE_POINTER_SWITCH",
                objectMapper.valueToTree(Map.of(
                        "rolledBackReleasePid", releasePid,
                        "reason", request.reason(),
                        "activeReleasePid", release.releasePid(),
                        "channelVersion", release.channelVersion())));
        return releaseView(release);
    }

    private WorkspaceRow requireOwnedSession(Identity identity, String sessionPid) {
        WorkspaceRow row = workspaceRepository.find(
                identity.tenantId(), identity.envId(), sessionPid, false);
        if (row == null) {
            throw new ResponseStatusException(NOT_FOUND, "authoring.session.not-found");
        }
        if (row.actorUserId() != identity.userId()) {
            throw new ResponseStatusException(FORBIDDEN, "authoring.session.actor-mismatch");
        }
        return row;
    }

    private GovernanceRow requireChangeSet(
            Identity identity,
            String changeSetPid,
            boolean lock) {
        GovernanceRow row = governanceRepository.findChangeSet(
                identity.tenantId(), identity.envId(), changeSetPid, lock);
        if (row == null) {
            throw new ResponseStatusException(NOT_FOUND, "authoring.change-set.not-found");
        }
        return row;
    }

    private ObjectNode releaseManifest(
            GovernanceRow row,
            ChannelRow channel,
            String releasePid,
            JsonNode snapshot) {
        ObjectNode manifest = objectMapper.createObjectNode();
        manifest.put("releasePid", releasePid);
        manifest.put("changeSetPid", row.changeSetPid());
        manifest.put("changeSetRevision", row.revision());
        if (channel != null) {
            manifest.put("previousReleasePid", channel.activeReleasePid());
        }
        ArrayNode resources = manifest.putArray("resources");
        ObjectNode resource = resources.addObject();
        resource.put("resourceType", "PAGE_SCHEMA");
        resource.put("resourcePid", row.resourcePid());
        resource.put("ownershipScope", row.ownershipScope());
        resource.put("sourceOwnershipScope", row.sourceOwnershipScope());
        resource.put("sourceResourcePid", row.sourceResourcePid());
        if (row.overridePid() != null) {
            resource.put("overridePid", row.overridePid());
        }
        resource.put("snapshotChecksum", snapshotFactory.checksum(snapshot));
        resource.put("sourceRevision", row.revision());
        return manifest;
    }

    private ChangeSetView view(GovernanceRow row) {
        return new ChangeSetView(
                row.changeSetPid(), row.resourcePid(), row.ownerUserId(), row.status(),
                row.revision(), row.riskLevel(), row.route(), row.publishPolicy(),
                row.validationState(), row.impactState(), row.approvalState(), row.publishState(),
                row.manifestChecksum());
    }

    private SessionView sessionView(Identity identity, String sessionPid) {
        return viewMapper.toView(requireOwnedSession(identity, sessionPid), identity.userId());
    }

    private ReleaseView releaseView(ReleaseRow row) {
        return new ReleaseView(
                row.releasePid(), row.changeSetPid(), row.changeSetRevision(),
                row.previousReleasePid(), row.status(), row.manifestChecksum(),
                row.channelVersion(), row.activatedAt());
    }

    private ReleaseHistoryItemView releaseHistoryItemView(ReleaseHistoryRow row) {
        return new ReleaseHistoryItemView(
                row.releasePid(), row.changeSetPid(), row.changeSetRevision(),
                row.previousReleasePid(), row.status(), reversibility(row),
                row.manifestChecksum(), row.createdAt(), row.activatedAt());
    }

    private RollbackEligibilityView rollbackEligibility(ReleaseChannelHistory channel) {
        if (channel == null) {
            return new RollbackEligibilityView(
                    false, "NO_ACTIVE_RELEASE", null, 0, 0, 0);
        }
        String reasonCode = "ELIGIBLE";
        boolean eligible = true;
        if (channel.previousReleasePid() == null) {
            eligible = false;
            reasonCode = "NO_PREVIOUS_RELEASE";
        } else if (!"SUPERSEDED".equals(channel.previousReleaseStatus())) {
            eligible = false;
            reasonCode = "PREVIOUS_RELEASE_UNAVAILABLE";
        } else if (channel.forwardOnlyItemCount() > 0) {
            eligible = false;
            reasonCode = "CONTAINS_FORWARD_ONLY_CHANGES";
        } else if (channel.compensatableItemCount() > 0) {
            eligible = false;
            reasonCode = "CONTAINS_COMPENSATABLE_CHANGES";
        }
        return new RollbackEligibilityView(
                eligible, reasonCode, channel.previousReleasePid(),
                channel.reversibleItemCount(), channel.compensatableItemCount(),
                channel.forwardOnlyItemCount());
    }

    private String reversibility(ReleaseHistoryRow row) {
        if (row.forwardOnlyItemCount() > 0) {
            return "FORWARD_ONLY";
        }
        if (row.compensatableItemCount() > 0) {
            return "COMPENSATABLE";
        }
        return "REVERSIBLE";
    }

    private ChangeItemView itemView(ChangeItem item) {
        return new ChangeItemView(
                item.pid(), item.sourceChangeItemPid(), item.blockId(), item.propertyPath(),
                item.operation(), item.riskLevel(), item.route(), item.publishPolicy(),
                item.reversibility(), item.actorUserId(), item.dependencySnapshot(),
                item.createdAt());
    }

    private ArrayNode splitLineage(GovernanceRow row) {
        ArrayNode lineage = row.lineage() != null && row.lineage().isArray()
                ? (ArrayNode) row.lineage().deepCopy()
                : objectMapper.createArrayNode();
        ObjectNode ancestor = lineage.addObject();
        ancestor.put("changeSetPid", row.changeSetPid());
        ancestor.put("revision", row.revision());
        ancestor.put("relation", "SPLIT_FROM");
        return lineage;
    }

    private ObjectNode splitDependencySnapshot(SplitPlan plan) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        ArrayNode source = snapshot.putArray("sourceItemPids");
        plan.sourceItems().forEach(item -> source.add(item.pid()));
        ArrayNode target = snapshot.putArray("targetItemPids");
        plan.targetItems().forEach(item -> target.add(item.pid()));
        ObjectNode sourceDependencies = snapshot.putObject("sourceDependencies");
        plan.sourceDependencySnapshots().forEach(sourceDependencies::set);
        ObjectNode targetDependencies = snapshot.putObject("targetDependencies");
        plan.targetDependencySnapshots().forEach(targetDependencies::set);
        snapshot.put("crossPartitionDependencies", false);
        return snapshot;
    }

    private String requireSplitText(String value, String reason) {
        if (value == null || value.isBlank()) {
            throw conflict(reason);
        }
        return value.trim();
    }

    private String safeReason(String reason) {
        return reason == null || reason.isBlank() ? null : reason.trim();
    }

    private String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw conflict("authoring.review.reason-required");
        }
        return reason.trim();
    }

    private void requireOwner(GovernanceRow row, Identity identity) {
        if (row.ownerUserId() != identity.userId()) {
            throw new ResponseStatusException(FORBIDDEN, "authoring.change-set.owner-required");
        }
    }

    private void requireResumableSession(WorkspaceRow workspace, long expectedRevision) {
        requireNonReviewWorkspace(workspace);
        if (!workspace.expiresAt().isAfter(Instant.now())
                || (!"ACTIVE".equals(workspace.sessionState())
                && !"READ_ONLY".equals(workspace.sessionState()))) {
            throw conflict("authoring.session.expired");
        }
        if (workspace.sessionRevision() != expectedRevision
                || workspace.resourceRevision() != expectedRevision) {
            throw conflict("authoring.revision.conflict");
        }
    }

    private void requireWritableSession(
            WorkspaceRow workspace,
            GovernanceRow row,
            Identity identity,
            long expectedRevision) {
        requireNonReviewWorkspace(workspace);
        Instant now = Instant.now();
        if (!workspace.expiresAt().isAfter(now)) {
            throw conflict("authoring.session.expired");
        }
        if (!"ACTIVE".equals(workspace.sessionState())) {
            throw conflict("authoring.session.read-only");
        }
        if (row.leaseSessionId() != workspace.sessionId()
                || row.leaseHolderUserId() != identity.userId()) {
            throw conflict("authoring.writer-lease.lost");
        }
        if (!workspace.leasedUntil().isAfter(now)) {
            throw conflict("authoring.writer-lease.expired");
        }
        if (row.revision() != expectedRevision
                || workspace.sessionRevision() != expectedRevision
                || workspace.resourceRevision() != expectedRevision) {
            throw conflict("authoring.revision.conflict");
        }
    }

    private void requireNonReviewWorkspace(WorkspaceRow workspace) {
        if (REVIEW_WORKSPACE_MODE.equals(workspace.workspaceMode())) {
            throw new ResponseStatusException(FORBIDDEN, "authoring.review.workspace-read-only");
        }
    }

    private JsonNode revisionTransition(GovernanceRow row, String reason) {
        return objectMapper.valueToTree(Map.of(
                "reason", reason.trim(),
                "decisionRevision", row.revision(),
                "resultRevision", row.revision() + 1));
    }

    private Identity identity() {
        MetaContext context = MetaContext.get();
        Long envId = MetaContext.getCurrentEnvironmentId();
        if (context.getTenantId() == null || context.getUserId() == null || envId == null) {
            throw new ResponseStatusException(FORBIDDEN, "authoring.context.incomplete");
        }
        return new Identity(context.getTenantId(), envId, context.getUserId());
    }

    private void audit(
            Identity identity,
            GovernanceRow row,
            String sessionPid,
            String eventType,
            String result,
            String reasonCode,
            JsonNode metadata) {
        workspaceRepository.audit(new AuditEntry(
                UniqueIdGenerator.generate(), identity.tenantId(), identity.envId(),
                identity.userId(), row.changeSetPid(), sessionPid, eventType, result,
                reasonCode, "PAGE_SCHEMA", row.resourcePid(), null, null,
                MetaContext.getOtelTraceId(),
                metadata == null ? objectMapper.createObjectNode() : metadata));
    }

    private void recordPublishFailure(
            Identity identity,
            GovernanceRow row,
            String changeSetPid,
            RevisionRequest request,
            RuntimeException failure) {
        ObjectNode metadata = objectMapper.createObjectNode();
        if (request != null) {
            metadata.put("expectedRevision", request.expectedRevision());
        }
        metadata.put("failureCategory", publishFailureCategory(failure));
        AuditEntry entry = new AuditEntry(
                UniqueIdGenerator.generate(), identity.tenantId(), identity.envId(),
                identity.userId(), changeSetPid, null,
                "RELEASE_PUBLISH_FAILED", "FAIL", publishFailureReason(failure),
                "PAGE_SCHEMA", row == null ? null : row.resourcePid(), null, null,
                MetaContext.getOtelTraceId(), metadata);
        try {
            auditService.recordDenied(entry);
        } catch (RuntimeException auditFailure) {
            failure.addSuppressed(auditFailure);
        }
    }

    private String publishFailureReason(RuntimeException failure) {
        if (failure instanceof AuthoringStaleStateException) {
            return "PUBLISH_STALE";
        }
        if (failure instanceof ResponseStatusException response) {
            if (response.getReason() == null) {
                return "PUBLISH_REJECTED";
            }
            String reason = response.getReason().toUpperCase(Locale.ROOT)
                    .replace('.', '_')
                    .replace('-', '_');
            return reason.length() <= 80 ? reason : "PUBLISH_REJECTED";
        }
        if (failure instanceof DataAccessException) {
            return "PUBLISH_PERSISTENCE_FAILED";
        }
        return "PUBLISH_FAILED";
    }

    private String publishFailureCategory(RuntimeException failure) {
        if (failure instanceof AuthoringStaleStateException) {
            return "STALE";
        }
        if (failure instanceof ResponseStatusException) {
            return "REJECTED";
        }
        if (failure instanceof DataAccessException) {
            return "PERSISTENCE";
        }
        return "UNEXPECTED";
    }

    private ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(CONFLICT, reason);
    }

    private record Identity(long tenantId, long envId, long userId) {
    }
}
