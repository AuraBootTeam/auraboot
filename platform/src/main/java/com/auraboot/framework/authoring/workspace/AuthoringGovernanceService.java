package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.authoring.workspace.AuthoringGovernanceRepository.ChannelRow;
import com.auraboot.framework.authoring.workspace.AuthoringGovernanceRepository.GovernanceRow;
import com.auraboot.framework.authoring.workspace.AuthoringGovernanceRepository.ReleaseRow;
import com.auraboot.framework.authoring.workspace.AuthoringGovernanceRepository.RollbackRow;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ChangeSetView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ReleaseView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ReviewRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.RevisionRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.RollbackRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ResumeEditingRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceRepository.AuditEntry;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceRepository.WorkspaceRow;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/** Review, publish and rollback state machine for one frozen ChangeSet revision. */
@Service
public class AuthoringGovernanceService {

    private static final Duration WRITER_LEASE = Duration.ofMinutes(5);
    private static final String REVIEW_WORKSPACE_MODE = "REVIEW";

    private final AuthoringGovernanceRepository governanceRepository;
    private final AuthoringWorkspaceRepository workspaceRepository;
    private final AuthoringGovernanceValidator governanceValidator;
    private final AuthoringPageSnapshotFactory snapshotFactory;
    private final AuthoringRuntimeSnapshotSanitizer runtimeSanitizer;
    private final ObjectMapper objectMapper;

    public AuthoringGovernanceService(
            AuthoringGovernanceRepository governanceRepository,
            AuthoringWorkspaceRepository workspaceRepository,
            AuthoringGovernanceValidator governanceValidator,
            AuthoringPageSnapshotFactory snapshotFactory,
            AuthoringRuntimeSnapshotSanitizer runtimeSanitizer,
            ObjectMapper objectMapper) {
        this.governanceRepository = governanceRepository;
        this.workspaceRepository = workspaceRepository;
        this.governanceValidator = governanceValidator;
        this.snapshotFactory = snapshotFactory;
        this.runtimeSanitizer = runtimeSanitizer;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ChangeSetView submit(String sessionPid, RevisionRequest request) {
        Identity identity = identity();
        WorkspaceRow workspace = requireOwnedSession(identity, sessionPid);
        GovernanceRow row = requireChangeSet(identity, workspace.changeSetPid(), true);
        requireWritableSession(workspace, row, identity, request.expectedRevision());
        governanceValidator.requireRevision(row, request.expectedRevision());
        governanceValidator.requireStatus(row, "DRAFT", "REJECTED");
        governanceValidator.requireFresh(row);
        if (governanceRepository.countItems(row) == 0) {
            throw conflict("authoring.submit.empty");
        }
        boolean approvalRequired = governanceValidator.approvalRequired(row);
        governanceRepository.submit(row, approvalRequired, identity.userId());
        audit(identity, row, sessionPid, "CHANGE_SET_SUBMITTED", "ALLOW",
                approvalRequired ? "REVIEW_REQUIRED" : "DIRECT_ALLOWED", null);
        return view(requireChangeSet(identity, row.changeSetPid(), false));
    }

    @Transactional
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

    @Transactional
    public ReleaseView publish(String changeSetPid, RevisionRequest request) {
        Identity identity = identity();
        GovernanceRow row = requireChangeSet(identity, changeSetPid, true);
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
        resource.put("snapshotChecksum", snapshotFactory.checksum(snapshot));
        resource.put("sourceRevision", row.revision());
        return manifest;
    }

    private ChangeSetView view(GovernanceRow row) {
        return new ChangeSetView(
                row.changeSetPid(), row.resourcePid(), row.ownerUserId(), row.status(),
                row.revision(), row.riskLevel(), row.route(), row.publishPolicy(),
                row.validationState(), row.approvalState(), row.publishState(),
                row.manifestChecksum());
    }

    private ReleaseView releaseView(ReleaseRow row) {
        return new ReleaseView(
                row.releasePid(), row.changeSetPid(), row.changeSetRevision(),
                row.previousReleasePid(), row.status(), row.manifestChecksum(),
                row.channelVersion(), row.activatedAt());
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

    private ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(CONFLICT, reason);
    }

    private record Identity(long tenantId, long envId, long userId) {
    }
}
