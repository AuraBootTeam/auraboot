package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.authoring.policy.AuthoringCapabilityRegistry;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.CapabilityManifest;
import com.auraboot.framework.authoring.workspace.AuthoringPatchEngine.PreparedPatch;
import com.auraboot.framework.authoring.workspace.AuthoringActiveReleaseResolver.ActiveRelease;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ApplyPatchRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.CapabilityRegistryView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.OpenSessionRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.PatchResult;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.SessionView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceRepository.AggregatePolicy;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceRepository.AuditEntry;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceRepository.CreateWorkspace;
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
        return viewMapper.toView(requireWorkspace(identity, sessionPid, false));
    }

    @Transactional(readOnly = true)
    public SessionView get(String sessionPid) {
        Identity identity = identity();
        return viewMapper.toView(requireWorkspace(identity, sessionPid, false));
    }

    @Transactional
    public PatchResult apply(String sessionPid, ApplyPatchRequest request) {
        Identity identity = identity();
        WorkspaceRow workspace = requireWorkspace(identity, sessionPid, true);

        PreparedPatch prepared;
        try {
            validateWritable(workspace, identity, request.expectedRevision());
            prepared = patchEngine.prepare(
                    workspace.snapshot(),
                    request.blockId(),
                    request.propertyPath(),
                    request.operation(),
                    request.value(),
                    request.manifestChecksum(),
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
                            "expectedRevision", request.expectedRevision()))));
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
                        "route", prepared.decision().route().name()))));

        SessionView reloaded = viewMapper.toView(requireWorkspace(identity, sessionPid, false));
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
        if (!"ACTIVE".equals(row.sessionState()) || !row.expiresAt().isAfter(now)) {
            throw new ResponseStatusException(CONFLICT, "authoring.session.expired");
        }
        if (!row.leasedUntil().isAfter(now)) {
            throw new ResponseStatusException(CONFLICT, "authoring.writer-lease.expired");
        }
        if (row.actorUserId() != identity.userId()) {
            throw new ResponseStatusException(FORBIDDEN, "authoring.writer-lease.actor-mismatch");
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
