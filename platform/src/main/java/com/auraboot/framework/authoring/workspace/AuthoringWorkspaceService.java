package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.authoring.policy.AuthoringCapabilityRegistry;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.CapabilityManifest;
import com.auraboot.framework.authoring.workspace.AuthoringPatchEngine.PreparedPatch;
import com.auraboot.framework.authoring.workspace.AuthoringActiveReleaseResolver.ActiveRelease;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ApplyPatchRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.CapabilityRegistryView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.MoveBlockRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ObserveChangeSetRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.OpenSessionRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.PatchResult;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.SessionView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.TakeoverWriterLeaseRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceRepository.AggregatePolicy;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceRepository.AuditEntry;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceRepository.CreateWorkspace;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceRepository.CreateObserverSession;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceRepository.WorkspaceRow;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.auraboot.framework.authoring.policy.CoreAuthoringCapabilityRegistry.REORDER_WITHIN_PARENT_PATH;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/** Transactional contextual-authoring workspace; it never invokes a business command. */
@Service
public class AuthoringWorkspaceService {

    private static final Duration SESSION_TTL = Duration.ofHours(8);
    private static final Duration WRITER_LEASE = Duration.ofMinutes(5);
    private final AuthoringCapabilityRegistry capabilityRegistry;
    private final AuthoringPatchEngine patchEngine;
    private final AuthoringWorkspaceRepository repository;
    private final AuthoringAuditService auditService;
    private final PageSchemaMapper pageSchemaMapper;
    private final ObjectMapper objectMapper;
    private final AuthoringPageSnapshotFactory snapshotFactory;
    private final AuthoringInteractionContextSanitizer interactionContextSanitizer;
    private final AuthoringAggregatePolicyService aggregatePolicyService;
    private final AuthoringWorkspaceViewMapper viewMapper;
    private final AuthoringActiveReleaseResolver activeReleaseResolver;

    public AuthoringWorkspaceService(
            AuthoringCapabilityRegistry capabilityRegistry,
            AuthoringPatchEngine patchEngine,
            AuthoringWorkspaceRepository repository,
            AuthoringAuditService auditService,
            PageSchemaMapper pageSchemaMapper,
            ObjectMapper objectMapper,
            AuthoringPageSnapshotFactory snapshotFactory,
            AuthoringInteractionContextSanitizer interactionContextSanitizer,
            AuthoringAggregatePolicyService aggregatePolicyService,
            AuthoringWorkspaceViewMapper viewMapper,
            AuthoringActiveReleaseResolver activeReleaseResolver) {
        this.capabilityRegistry = capabilityRegistry;
        this.patchEngine = patchEngine;
        this.repository = repository;
        this.auditService = auditService;
        this.pageSchemaMapper = pageSchemaMapper;
        this.objectMapper = objectMapper;
        this.snapshotFactory = snapshotFactory;
        this.interactionContextSanitizer = interactionContextSanitizer;
        this.aggregatePolicyService = aggregatePolicyService;
        this.viewMapper = viewMapper;
        this.activeReleaseResolver = activeReleaseResolver;
    }

    public CapabilityRegistryView capabilities() {
        List<CapabilityManifest> manifests = capabilityRegistry.all().stream()
                .sorted(Comparator.comparing(CapabilityManifest::blockType))
                .toList();
        return new CapabilityRegistryView(capabilityRegistry.checksum(), manifests);
    }

    @Transactional
    public SessionView open(OpenSessionRequest request) {
        Identity identity = identity();
        PageSchema page = pageSchemaMapper.selectByPid(request.pagePid());
        if (page == null || !identity.tenantId().equals(page.getTenantId())) {
            throw new ResponseStatusException(NOT_FOUND, "authoring.page.not-found");
        }
        if (page.getEnvId() != null && !identity.envId().equals(page.getEnvId())) {
            throw new ResponseStatusException(NOT_FOUND, "authoring.page.not-found");
        }

        ActiveRelease activeRelease = activeReleaseResolver.findByResource(
                identity.tenantId(), identity.envId(), "PAGE_SCHEMA", page.getPid());
        JsonNode snapshot = activeRelease == null
                ? snapshotFactory.create(page)
                : activeRelease.snapshot();
        JsonNode interactionContext = interactionContextSanitizer.sanitize(request.interactionContext());
        String sessionPid = UniqueIdGenerator.generate();
        String changeSetPid = UniqueIdGenerator.generate();
        Instant now = Instant.now();
        repository.create(new CreateWorkspace(
                identity.tenantId(),
                identity.envId(),
                identity.userId(),
                sessionPid,
                changeSetPid,
                UniqueIdGenerator.generate(),
                UniqueIdGenerator.generate(),
                page.getPid(),
                snapshotFactory.title(page),
                activeRelease == null ? null : activeRelease.releasePid(),
                activeRelease == null
                        ? snapshotFactory.baseVersion(page)
                        : activeRelease.channelVersion(),
                activeRelease == null
                        ? snapshotFactory.checksum(snapshot)
                        : activeRelease.snapshotChecksum(),
                capabilityRegistry.checksum(),
                snapshot,
                interactionContext,
                now.plus(SESSION_TTL),
                now.plus(WRITER_LEASE)));
        repository.audit(audit(identity, changeSetPid, sessionPid, "SESSION_OPENED", "ALLOW",
                null, page.getPid(), null, null,
                objectMapper.valueToTree(Map.of("baseVersion", snapshotFactory.baseVersion(page)))));
        return viewMapper.toView(requireWorkspace(identity, sessionPid, false), identity.userId());
    }

    @Transactional(readOnly = true)
    public SessionView get(String sessionPid) {
        Identity identity = identity();
        return viewMapper.toView(requireWorkspace(identity, sessionPid, false), identity.userId());
    }

    @Transactional
    public SessionView observe(String changeSetPid, ObserveChangeSetRequest request) {
        Identity identity = identity();
        String sessionPid = UniqueIdGenerator.generate();
        JsonNode interactionContext = interactionContextSanitizer.sanitize(
                request == null ? null : request.interactionContext());
        String created = repository.createObserverSession(new CreateObserverSession(
                identity.tenantId(),
                identity.envId(),
                identity.userId(),
                changeSetPid,
                sessionPid,
                interactionContext,
                Instant.now().plus(SESSION_TTL)));
        if (created == null) {
            throw new ResponseStatusException(NOT_FOUND, "authoring.change-set.not-found");
        }
        WorkspaceRow workspace = requireWorkspace(identity, created, false);
        repository.audit(audit(
                identity,
                workspace.changeSetPid(),
                workspace.sessionPid(),
                "OBSERVER_SESSION_OPENED",
                "ALLOW",
                null,
                workspace.pagePid(),
                null,
                null,
                objectMapper.valueToTree(Map.of(
                        "leaseRevision", workspace.leaseRevision(),
                        "writerLeaseStatus", "HELD_BY_OTHER"))));
        return viewMapper.toView(workspace, identity.userId());
    }

    @Transactional
    public SessionView takeoverWriterLease(
            String sessionPid,
            TakeoverWriterLeaseRequest request) {
        Identity identity = identity();
        WorkspaceRow workspace = requireWorkspace(identity, sessionPid, true);
        if (workspace.changeSetRevision() != request.expectedRevision()) {
            throw new ResponseStatusException(CONFLICT, "authoring.revision.conflict");
        }
        if (!"DRAFT".equals(workspace.changeSetStatus())
                && !"REJECTED".equals(workspace.changeSetStatus())) {
            throw new ResponseStatusException(CONFLICT, "authoring.writer-lease.change-set-frozen");
        }
        Instant now = Instant.now();
        boolean alreadyOwned = workspace.leaseSessionId() == workspace.sessionId()
                && workspace.leaseHolderUserId() == identity.userId()
                && workspace.leasedUntil().isAfter(now)
                && "ACTIVE".equals(workspace.sessionState());
        if (alreadyOwned) {
            return viewMapper.toView(workspace, identity.userId());
        }

        long previousHolderUserId = workspace.leaseHolderUserId();
        long previousLeaseRevision = workspace.leaseRevision();
        repository.takeoverWriterLease(
                workspace,
                identity.userId(),
                now.plus(SESSION_TTL),
                now.plus(WRITER_LEASE));
        WorkspaceRow reloaded = requireWorkspace(identity, sessionPid, false);
        repository.audit(audit(
                identity,
                reloaded.changeSetPid(),
                reloaded.sessionPid(),
                "WRITER_LEASE_TAKEN_OVER",
                "ALLOW",
                "EXPLICIT_ADMIN_TAKEOVER",
                reloaded.pagePid(),
                null,
                null,
                objectMapper.valueToTree(Map.of(
                        "reason", request.reason().trim(),
                        "previousHolderUserId", previousHolderUserId,
                        "previousLeaseRevision", previousLeaseRevision,
                        "resultLeaseRevision", reloaded.leaseRevision()))));
        return viewMapper.toView(reloaded, identity.userId());
    }

    @Transactional
    public PatchResult apply(String sessionPid, ApplyPatchRequest request) {
        return apply(sessionPid, request, false);
    }

    @Transactional
    public PatchResult applyStudio(String sessionPid, ApplyPatchRequest request) {
        return apply(sessionPid, request, true);
    }

    @Transactional
    public PatchResult moveStudioBlock(String sessionPid, MoveBlockRequest request) {
        Identity identity = identity();
        WorkspaceRow workspace = requireWorkspace(identity, sessionPid, true);

        PreparedPatch prepared;
        try {
            validateWritable(workspace, identity, request.expectedRevision());
            prepared = patchEngine.prepareStudioMove(
                    workspace.snapshot(),
                    request.blockId(),
                    request.beforeBlockId(),
                    request.manifestChecksum(),
                    snapshotFactory.resourceScope(workspace.snapshot()));
        } catch (ResponseStatusException exception) {
            auditService.recordDenied(audit(
                    identity,
                    workspace.changeSetPid(),
                    workspace.sessionPid(),
                    "STRUCTURE_MOVE_DENIED",
                    "DENY",
                    reason(exception),
                    workspace.pagePid(),
                    request.blockId(),
                    REORDER_WITHIN_PARENT_PATH,
                    moveMetadata(request, null)));
            throw exception;
        }

        String changeItemPid = UniqueIdGenerator.generate();
        AggregatePolicy aggregate = aggregatePolicyService.aggregate(workspace, prepared.decision());
        repository.persistPatch(
                workspace,
                prepared.snapshot(),
                capabilityRegistry.checksum(),
                changeItemPid,
                request.blockId(),
                REORDER_WITHIN_PARENT_PATH,
                "MOVE",
                prepared.previousValue(),
                prepared.savedValue(),
                prepared.capability(),
                prepared.decision(),
                identity.userId(),
                aggregate,
                Instant.now().plus(WRITER_LEASE));
        repository.audit(audit(
                identity,
                workspace.changeSetPid(),
                workspace.sessionPid(),
                "STRUCTURE_MOVE_SAVED",
                "ALLOW",
                prepared.decision().reason().name(),
                workspace.pagePid(),
                request.blockId(),
                REORDER_WITHIN_PARENT_PATH,
                moveMetadata(request, changeItemPid)));

        SessionView reloaded = viewMapper.toView(
                requireWorkspace(identity, sessionPid, false), identity.userId());
        return new PatchResult(
                reloaded,
                changeItemPid,
                prepared.decision(),
                prepared.previousValue(),
                prepared.savedValue());
    }

    private PatchResult apply(
            String sessionPid,
            ApplyPatchRequest request,
            boolean studioRoute) {
        Identity identity = identity();
        WorkspaceRow workspace = requireWorkspace(identity, sessionPid, true);

        PreparedPatch prepared;
        try {
            validateWritable(workspace, identity, request.expectedRevision());
            prepared = studioRoute
                    ? patchEngine.prepareStudio(
                            workspace.snapshot(), request.blockId(), request.propertyPath(),
                            request.operation(), request.value(), request.manifestChecksum(),
                            snapshotFactory.resourceScope(workspace.snapshot()))
                    : patchEngine.prepareInline(
                            workspace.snapshot(), request.blockId(), request.propertyPath(),
                            request.operation(), request.value(), request.manifestChecksum(),
                            snapshotFactory.resourceScope(workspace.snapshot()));
        } catch (ResponseStatusException exception) {
            auditService.recordDenied(audit(
                    identity,
                    workspace.changeSetPid(),
                    workspace.sessionPid(),
                    "PATCH_DENIED",
                    "DENY",
                    reason(exception),
                    workspace.pagePid(),
                    request.blockId(),
                    request.propertyPath(),
                    objectMapper.valueToTree(Map.of(
                            "operation", request.operation().name(),
                            "expectedRevision", request.expectedRevision(),
                            "authoringSurface", studioRoute ? "STUDIO" : "CONTEXTUAL"))));
            throw exception;
        }

        String changeItemPid = UniqueIdGenerator.generate();
        AggregatePolicy aggregate = aggregatePolicyService.aggregate(workspace, prepared.decision());
        repository.persistPatch(
                workspace,
                prepared.snapshot(),
                capabilityRegistry.checksum(),
                changeItemPid,
                request.blockId(),
                request.propertyPath(),
                request.operation().name(),
                prepared.previousValue(),
                prepared.savedValue(),
                prepared.capability(),
                prepared.decision(),
                identity.userId(),
                aggregate,
                Instant.now().plus(WRITER_LEASE));
        repository.audit(audit(
                identity,
                workspace.changeSetPid(),
                workspace.sessionPid(),
                "PATCH_SAVED",
                "ALLOW",
                prepared.decision().reason().name(),
                workspace.pagePid(),
                request.blockId(),
                request.propertyPath(),
                objectMapper.valueToTree(Map.of(
                        "changeItemPid", changeItemPid,
                        "operation", request.operation().name(),
                        "resultRevision", request.expectedRevision() + 1,
                        "riskLevel", prepared.decision().risk().name(),
                        "route", prepared.decision().route().name(),
                        "authoringSurface", studioRoute ? "STUDIO" : "CONTEXTUAL"))));

        SessionView reloaded = viewMapper.toView(
                requireWorkspace(identity, sessionPid, false), identity.userId());
        return new PatchResult(reloaded, changeItemPid, prepared.decision(),
                prepared.previousValue(), prepared.savedValue());
    }

    private WorkspaceRow requireWorkspace(Identity identity, String sessionPid, boolean lock) {
        WorkspaceRow row = repository.find(identity.tenantId(), identity.envId(), sessionPid, lock);
        if (row == null) {
            throw new ResponseStatusException(NOT_FOUND, "authoring.session.not-found");
        }
        if (row.actorUserId() != identity.userId()) {
            throw new ResponseStatusException(FORBIDDEN, "authoring.session.actor-mismatch");
        }
        return row;
    }

    private void validateWritable(WorkspaceRow row, Identity identity, long expectedRevision) {
        Instant now = Instant.now();
        if (!row.expiresAt().isAfter(now)) {
            throw new ResponseStatusException(CONFLICT, "authoring.session.expired");
        }
        if (row.leaseSessionId() != row.sessionId()
                || row.leaseHolderUserId() != identity.userId()) {
            throw new ResponseStatusException(CONFLICT, "authoring.writer-lease.lost");
        }
        if (!"ACTIVE".equals(row.sessionState())) {
            throw new ResponseStatusException(CONFLICT, "authoring.session.read-only");
        }
        if (!row.leasedUntil().isAfter(now)) {
            throw new ResponseStatusException(CONFLICT, "authoring.writer-lease.expired");
        }
        if (row.changeSetRevision() != expectedRevision
                || row.resourceRevision() != expectedRevision
                || row.sessionRevision() != expectedRevision) {
            throw new ResponseStatusException(CONFLICT, "authoring.revision.conflict");
        }
    }

    private Identity identity() {
        MetaContext context = MetaContext.get();
        Long envId = MetaContext.getCurrentEnvironmentId();
        if (context.getTenantId() == null || context.getUserId() == null || envId == null) {
            throw new ResponseStatusException(FORBIDDEN, "authoring.context.incomplete");
        }
        return new Identity(context.getTenantId(), envId, context.getUserId());
    }

    private JsonNode moveMetadata(MoveBlockRequest request, String changeItemPid) {
        var metadata = objectMapper.createObjectNode();
        metadata.put("operation", "MOVE");
        metadata.put("expectedRevision", request.expectedRevision());
        metadata.put("authoringSurface", "STUDIO");
        metadata.put("structureOperation", "REORDER_WITHIN_PARENT");
        if (request.beforeBlockId() == null) {
            metadata.putNull("beforeBlockId");
        } else {
            metadata.put("beforeBlockId", request.beforeBlockId());
        }
        if (changeItemPid != null) {
            metadata.put("changeItemPid", changeItemPid);
            metadata.put("resultRevision", request.expectedRevision() + 1);
        }
        return metadata;
    }

    private String reason(ResponseStatusException exception) {
        return exception.getReason() == null
                ? "AUTHORING_DENIED"
                : exception.getReason().toUpperCase(Locale.ROOT).replace('.', '_');
    }

    private AuditEntry audit(
            Identity identity,
            String changeSetPid,
            String sessionPid,
            String eventType,
            String result,
            String reasonCode,
            String resourcePid,
            String blockId,
            String propertyPath,
            JsonNode metadata) {
        return new AuditEntry(
                UniqueIdGenerator.generate(),
                identity.tenantId(),
                identity.envId(),
                identity.userId(),
                changeSetPid,
                sessionPid,
                eventType,
                result,
                reasonCode,
                "PAGE_SCHEMA",
                resourcePid,
                blockId,
                propertyPath,
                MetaContext.getOtelTraceId(),
                metadata == null ? objectMapper.createObjectNode() : metadata);
    }

    private record Identity(Long tenantId, Long envId, Long userId) {
    }

}
