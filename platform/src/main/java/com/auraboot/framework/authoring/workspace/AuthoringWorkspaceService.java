package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.authoring.policy.AuthoringCapabilityRegistry;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.CapabilityManifest;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.BoundaryDecision;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.EffectTag;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.PatchOperation;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.PropertyCapability;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.PublishPolicy;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.Reason;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.Reversibility;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.RiskLevel;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.Route;
import com.auraboot.framework.authoring.workspace.AuthoringPatchEngine.PreparedPatch;
import com.auraboot.framework.authoring.workspace.AuthoringActiveReleaseResolver.ActiveRelease;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ApplyPatchRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.CapabilityRegistryView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.CreateBlockRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.CreateNewPageWorkspaceRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.MoveBlockRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.NewPageWorkspaceOptions;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ObserveChangeSetRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.OpenSessionRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.PatchResult;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.RelocateBlockRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.RemoveBlockRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ReviewWorkspaceView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.SessionView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.TakeoverWriterLeaseRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceRepository.AggregatePolicy;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceRepository.AuditEntry;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceRepository.CreateWorkspace;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceRepository.CreateObserverSession;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceRepository.WorkspaceRow;
import com.auraboot.framework.authoring.workspace.AuthoringOwnershipService.OwnershipContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.auraboot.framework.authoring.policy.CoreAuthoringCapabilityRegistry.REORDER_WITHIN_PARENT_PATH;
import static com.auraboot.framework.authoring.policy.CoreAuthoringCapabilityRegistry.CREATE_BLOCK_PATH;
import static com.auraboot.framework.authoring.policy.CoreAuthoringCapabilityRegistry.RELOCATE_BLOCK_PATH;
import static com.auraboot.framework.authoring.policy.CoreAuthoringCapabilityRegistry.REMOVE_BLOCK_PATH;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;

/** Transactional contextual-authoring workspace; it never invokes a business command. */
@Service
public class AuthoringWorkspaceService {

    private static final Duration SESSION_TTL = Duration.ofHours(8);
    private static final Duration WRITER_LEASE = Duration.ofMinutes(5);
    private static final String REVIEW_WORKSPACE_MODE = "REVIEW";
    private static final Set<String> NEW_PAGE_KINDS = Set.of("list", "form", "detail");
    private static final String RESOURCE_BLOCK_ID = "$resource";
    private static final String CREATE_PAGE_PATH = "/$resource/page";
    private static final String CREATE_MENU_PATH = "/$resource/menu";
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
    private final AuthoringOwnershipService ownershipService;
    private final AuthoringNewPageMaterializer newPageMaterializer;

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
            AuthoringActiveReleaseResolver activeReleaseResolver,
            AuthoringOwnershipService ownershipService,
            AuthoringNewPageMaterializer newPageMaterializer) {
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
        this.ownershipService = ownershipService;
        this.newPageMaterializer = newPageMaterializer;
    }

    public CapabilityRegistryView capabilities() {
        List<CapabilityManifest> manifests = capabilityRegistry.all().stream()
                .sorted(Comparator.comparing(CapabilityManifest::blockType))
                .toList();
        return new CapabilityRegistryView(capabilityRegistry.checksum(), manifests);
    }

    public NewPageWorkspaceOptions newPageOptions() {
        Identity identity = identity();
        return newPageMaterializer.options(identity.tenantId(), identity.envId());
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
        JsonNode sourceSnapshot = activeRelease == null
                ? snapshotFactory.create(page)
                : activeRelease.snapshot();
        long baseVersion = activeRelease == null
                ? snapshotFactory.baseVersion(page)
                : activeRelease.channelVersion();
        String baseChecksum = activeRelease == null
                ? snapshotFactory.checksum(sourceSnapshot)
                : activeRelease.snapshotChecksum();
        OwnershipContext ownership = ownershipService.resolve(
                page, identity.tenantId(), identity.envId(), identity.userId(),
                baseVersion, baseChecksum);
        JsonNode snapshot = ownershipService.decorate(sourceSnapshot, ownership);
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
                ownership.origin(),
                ownership.ownershipScope(),
                ownership.sourceOwnershipScope(),
                ownership.sourceResourcePid(),
                ownership.overridePid(),
                activeRelease == null ? null : activeRelease.releasePid(),
                baseVersion,
                baseChecksum,
                capabilityRegistry.checksum(),
                snapshot,
                interactionContext,
                now.plus(SESSION_TTL),
                now.plus(WRITER_LEASE)));
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("baseVersion", baseVersion);
        metadata.put("ownershipScope", ownership.ownershipScope());
        metadata.put("sourceOwnershipScope", ownership.sourceOwnershipScope());
        metadata.put("sourceResourcePid", ownership.sourceResourcePid());
        metadata.put("origin", ownership.origin());
        metadata.put("overrideCreated", ownership.overrideCreated());
        if (ownership.overridePid() != null) {
            metadata.put("overridePid", ownership.overridePid());
        }
        repository.audit(audit(identity, changeSetPid, sessionPid, "SESSION_OPENED", "ALLOW",
                null, page.getPid(), null, null, metadata));
        if (ownership.overridePid() != null) {
            repository.audit(audit(
                    identity, changeSetPid, sessionPid,
                    ownership.overrideCreated()
                            ? "TENANT_OVERRIDE_CREATED"
                            : "TENANT_OVERRIDE_REUSED",
                    "ALLOW", "SHARED_SOURCE_IMMUTABLE", page.getPid(), null, null, metadata));
        }
        return viewMapper.toView(requireWorkspace(identity, sessionPid, false), identity.userId());
    }

    @Transactional
    public SessionView createNewPageWorkspace(
            String sourceSessionPid,
            CreateNewPageWorkspaceRequest request) {
        Identity identity = identity();
        WorkspaceRow source = requireWorkspace(identity, sourceSessionPid, true);
        validateWritable(source, identity, request.expectedSourceRevision());
        requireSupportedNewPageKind(request.kind());
        newPageMaterializer.requireAvailable(
                identity.tenantId(), identity.envId(), request.pageKey(), request.menuCode(),
                request.menuPath(), request.modelCode(), request.parentMenuCode(),
                request.permissionCode());

        String pagePid = UniqueIdGenerator.generate();
        String sessionPid = UniqueIdGenerator.generate();
        String changeSetPid = UniqueIdGenerator.generate();
        ObjectNode pageDefinition = newPageDefinition(identity, pagePid, request);
        ObjectNode menuDefinition = newMenuDefinition(request);
        ObjectNode snapshot = pageDefinition.deepCopy();
        ObjectNode resourceMetadata = snapshot.putObject(AuthoringNewPageMaterializer.RESOURCE_METADATA);
        resourceMetadata.put("lifecycle", AuthoringNewPageMaterializer.NEW_LIFECYCLE);
        resourceMetadata.set("menu", menuDefinition);

        Instant now = Instant.now();
        JsonNode absentBase = objectMapper.createObjectNode();
        JsonNode interactionContext = source.interactionContext().deepCopy();
        repository.create(new CreateWorkspace(
                identity.tenantId(), identity.envId(), identity.userId(), sessionPid,
                changeSetPid, UniqueIdGenerator.generate(), UniqueIdGenerator.generate(), pagePid,
                "Create page " + request.pageKey(), "DESIGN_STUDIO", "TENANT", "TENANT",
                pagePid, null, null, 1, snapshotFactory.checksum(absentBase),
                capabilityRegistry.checksum(), snapshot, interactionContext,
                now.plus(SESSION_TTL), now.plus(WRITER_LEASE)));

        PropertyCapability pageCapability = newResourceCapability(
                CREATE_PAGE_PATH, EffectTag.MODEL, EffectTag.NAVIGATION);
        BoundaryDecision decision = newResourceDecision();
        WorkspaceRow created = requireWorkspace(identity, sessionPid, true);
        repository.persistPatch(
                created, snapshot, capabilityRegistry.checksum(), UniqueIdGenerator.generate(),
                RESOURCE_BLOCK_ID, CREATE_PAGE_PATH, "ADD", null, pageDefinition,
                pageCapability, decision, identity.userId(),
                aggregatePolicyService.aggregate(created, decision), now.plus(WRITER_LEASE));

        PropertyCapability menuCapability = newResourceCapability(
                CREATE_MENU_PATH, EffectTag.NAVIGATION, EffectTag.PERMISSION, EffectTag.SECURITY);
        WorkspaceRow pageAdded = requireWorkspace(identity, sessionPid, true);
        repository.persistPatch(
                pageAdded, snapshot, capabilityRegistry.checksum(), UniqueIdGenerator.generate(),
                RESOURCE_BLOCK_ID, CREATE_MENU_PATH, "ADD", null, menuDefinition,
                menuCapability, decision, identity.userId(),
                aggregatePolicyService.aggregate(pageAdded, decision), now.plus(WRITER_LEASE));

        WorkspaceRow reloaded = requireWorkspace(identity, sessionPid, false);
        ObjectNode auditMetadata = objectMapper.createObjectNode();
        auditMetadata.put("sourceSessionPid", sourceSessionPid);
        auditMetadata.put("sourcePagePid", source.pagePid());
        auditMetadata.put("pageKey", request.pageKey());
        auditMetadata.put("menuCode", request.menuCode());
        auditMetadata.put("parentMenuCode", request.parentMenuCode());
        auditMetadata.put("resultRevision", reloaded.changeSetRevision());
        repository.audit(audit(
                identity, changeSetPid, sessionPid, "NEW_PAGE_WORKSPACE_CREATED", "ALLOW",
                "STUDIO_NEW_RESOURCE_RESERVED", pagePid, RESOURCE_BLOCK_ID,
                CREATE_PAGE_PATH, auditMetadata));
        return viewMapper.toView(reloaded, identity.userId());
    }

    private void requireSupportedNewPageKind(String kind) {
        if (!NEW_PAGE_KINDS.contains(kind)) {
            throw new ResponseStatusException(
                    UNPROCESSABLE_ENTITY, "authoring.new-page.kind-unsupported");
        }
    }

    private ObjectNode newPageDefinition(
            Identity identity,
            String pagePid,
            CreateNewPageWorkspaceRequest request) {
        ObjectNode page = objectMapper.createObjectNode();
        page.put("pid", pagePid);
        page.put("pageKey", request.pageKey());
        page.put("name", request.name().trim());
        if (request.description() != null && !request.description().isBlank()) {
            page.put("description", request.description().trim());
        }
        page.put("kind", request.kind());
        page.put("modelCode", request.modelCode());
        page.put("schemaVersion", 4);
        page.put("profile", "admin");
        page.put("isTemplate", false);
        page.put("ownershipScope", "TENANT");
        page.put("ownershipRef", "tenant:" + identity.tenantId());
        page.putObject("title").put("zh-CN", request.title().trim());
        page.putObject("layout").put("type", "stack");
        page.putArray("blocks");
        return page;
    }

    private ObjectNode newMenuDefinition(CreateNewPageWorkspaceRequest request) {
        ObjectNode menu = objectMapper.createObjectNode();
        menu.put("parentCode", request.parentMenuCode());
        menu.put("code", request.menuCode());
        menu.put("name", request.menuName().trim());
        menu.put("path", request.menuPath());
        if (request.menuIcon() != null && !request.menuIcon().isBlank()) {
            menu.put("icon", request.menuIcon().trim());
        }
        menu.put("permissionCode", request.permissionCode());
        menu.put("orderNo", 0);
        return menu;
    }

    private PropertyCapability newResourceCapability(String path, EffectTag... effects) {
        return new PropertyCapability(
                path, Set.of(PatchOperation.ADD), Route.HANDOFF_STUDIO, RiskLevel.L3,
                EnumSet.copyOf(List.of(effects)), Reversibility.FORWARD_ONLY, false, true);
    }

    private BoundaryDecision newResourceDecision() {
        return new BoundaryDecision(
                Route.HANDOFF_STUDIO, RiskLevel.L3, PublishPolicy.STUDIO_APPROVAL,
                Reason.FORWARD_ONLY, capabilityRegistry.checksum(), true);
    }

    @Transactional(readOnly = true)
    public SessionView get(String sessionPid) {
        Identity identity = identity();
        return viewMapper.toView(requireWorkspace(identity, sessionPid, false), identity.userId());
    }

    @Transactional
    public SessionView observe(String changeSetPid, ObserveChangeSetRequest request) {
        return observe(changeSetPid, request, "OBSERVER", "OBSERVER_SESSION_OPENED");
    }

    private SessionView observe(
            String changeSetPid,
            ObserveChangeSetRequest request,
            String workspaceMode,
            String auditEventType) {
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
                workspaceMode,
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
                auditEventType,
                "ALLOW",
                null,
                workspace.pagePid(),
                null,
                null,
                objectMapper.valueToTree(Map.of(
                        "leaseRevision", workspace.leaseRevision(),
                        "workspaceMode", workspaceMode,
                        "writerLeaseStatus", "HELD_BY_OTHER"))));
        return viewMapper.toView(workspace, identity.userId());
    }

    @Transactional
    public ReviewWorkspaceView openReviewWorkspace(
            String changeSetPid,
            ObserveChangeSetRequest request) {
        SessionView session = observe(
                changeSetPid, request, REVIEW_WORKSPACE_MODE, "REVIEW_WORKSPACE_OPENED");
        return new ReviewWorkspaceView(session, capabilities());
    }

    @Transactional(readOnly = true)
    public ReviewWorkspaceView getReviewWorkspace(String sessionPid) {
        Identity identity = identity();
        WorkspaceRow workspace = requireWorkspace(identity, sessionPid, false);
        if (!REVIEW_WORKSPACE_MODE.equals(workspace.workspaceMode())) {
            throw new ResponseStatusException(FORBIDDEN, "authoring.review.workspace-required");
        }
        return new ReviewWorkspaceView(
                viewMapper.toView(workspace, identity.userId()), capabilities());
    }

    @Transactional
    public SessionView takeoverWriterLease(
            String sessionPid,
            TakeoverWriterLeaseRequest request) {
        Identity identity = identity();
        WorkspaceRow workspace = requireWorkspace(identity, sessionPid, true);
        requireNonReviewWorkspace(workspace);
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

    @Transactional
    public PatchResult createStudioBlock(String sessionPid, CreateBlockRequest request) {
        Identity identity = identity();
        WorkspaceRow workspace = requireWorkspace(identity, sessionPid, true);
        JsonNode metadata = structureMetadata(
                "CREATE_BLOCK", request.expectedRevision(), request.parentBlockId(),
                request.beforeBlockId(), null);
        PreparedPatch prepared;
        try {
            validateWritable(workspace, identity, request.expectedRevision());
            prepared = patchEngine.prepareStudioCreate(
                    workspace.snapshot(), request.blockId(), request.blockType(),
                    request.parentBlockId(), request.beforeBlockId(), request.manifestChecksum(),
                    snapshotFactory.resourceScope(workspace.snapshot()));
        } catch (ResponseStatusException exception) {
            auditService.recordDenied(audit(
                    identity, workspace.changeSetPid(), workspace.sessionPid(),
                    "STRUCTURE_CREATE_DENIED", "DENY", reason(exception), workspace.pagePid(),
                    request.blockId(), CREATE_BLOCK_PATH, metadata));
            throw exception;
        }
        return persistStructure(
                workspace, identity, prepared, request.blockId(), CREATE_BLOCK_PATH, "ADD",
                "STRUCTURE_CREATE_SAVED", metadata);
    }

    @Transactional
    public PatchResult removeStudioBlock(String sessionPid, RemoveBlockRequest request) {
        Identity identity = identity();
        WorkspaceRow workspace = requireWorkspace(identity, sessionPid, true);
        JsonNode metadata = structureMetadata(
                "REMOVE_BLOCK", request.expectedRevision(), null, null, null);
        PreparedPatch prepared;
        try {
            validateWritable(workspace, identity, request.expectedRevision());
            prepared = patchEngine.prepareStudioRemove(
                    workspace.snapshot(), request.blockId(), request.manifestChecksum(),
                    snapshotFactory.resourceScope(workspace.snapshot()));
        } catch (ResponseStatusException exception) {
            auditService.recordDenied(audit(
                    identity, workspace.changeSetPid(), workspace.sessionPid(),
                    "STRUCTURE_REMOVE_DENIED", "DENY", reason(exception), workspace.pagePid(),
                    request.blockId(), REMOVE_BLOCK_PATH, metadata));
            throw exception;
        }
        return persistStructure(
                workspace, identity, prepared, request.blockId(), REMOVE_BLOCK_PATH, "REMOVE",
                "STRUCTURE_REMOVE_SAVED", metadata);
    }

    @Transactional
    public PatchResult relocateStudioBlock(String sessionPid, RelocateBlockRequest request) {
        Identity identity = identity();
        WorkspaceRow workspace = requireWorkspace(identity, sessionPid, true);
        JsonNode metadata = structureMetadata(
                "RELOCATE_BLOCK", request.expectedRevision(), request.targetParentBlockId(),
                request.beforeBlockId(), null);
        PreparedPatch prepared;
        try {
            validateWritable(workspace, identity, request.expectedRevision());
            prepared = patchEngine.prepareStudioRelocate(
                    workspace.snapshot(), request.blockId(), request.targetParentBlockId(),
                    request.beforeBlockId(), request.manifestChecksum(),
                    snapshotFactory.resourceScope(workspace.snapshot()));
        } catch (ResponseStatusException exception) {
            auditService.recordDenied(audit(
                    identity, workspace.changeSetPid(), workspace.sessionPid(),
                    "STRUCTURE_RELOCATE_DENIED", "DENY", reason(exception), workspace.pagePid(),
                    request.blockId(), RELOCATE_BLOCK_PATH, metadata));
            throw exception;
        }
        return persistStructure(
                workspace, identity, prepared, request.blockId(), RELOCATE_BLOCK_PATH, "MOVE",
                "STRUCTURE_RELOCATE_SAVED", metadata);
    }

    private PatchResult persistStructure(
            WorkspaceRow workspace,
            Identity identity,
            PreparedPatch prepared,
            String blockId,
            String propertyPath,
            String operation,
            String eventType,
            JsonNode metadata) {
        String changeItemPid = UniqueIdGenerator.generate();
        AggregatePolicy aggregate = aggregatePolicyService.aggregate(workspace, prepared.decision());
        repository.persistPatch(
                workspace, prepared.snapshot(), capabilityRegistry.checksum(), changeItemPid,
                blockId, propertyPath, operation, prepared.previousValue(), prepared.savedValue(),
                prepared.capability(), prepared.decision(), identity.userId(), aggregate,
                Instant.now().plus(WRITER_LEASE));
        ((ObjectNode) metadata).put("changeItemPid", changeItemPid);
        ((ObjectNode) metadata).put("resultRevision", workspace.changeSetRevision() + 1);
        repository.audit(audit(
                identity, workspace.changeSetPid(), workspace.sessionPid(), eventType, "ALLOW",
                prepared.decision().reason().name(), workspace.pagePid(), blockId, propertyPath,
                metadata));
        SessionView reloaded = viewMapper.toView(
                requireWorkspace(identity, workspace.sessionPid(), false), identity.userId());
        return new PatchResult(
                reloaded, changeItemPid, prepared.decision(),
                prepared.previousValue(), prepared.savedValue());
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
        requireNonReviewWorkspace(row);
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

    private void requireNonReviewWorkspace(WorkspaceRow row) {
        if (REVIEW_WORKSPACE_MODE.equals(row.workspaceMode())) {
            throw new ResponseStatusException(FORBIDDEN, "authoring.review.workspace-read-only");
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

    private JsonNode structureMetadata(
            String structureOperation,
            long expectedRevision,
            String parentBlockId,
            String beforeBlockId,
            String changeItemPid) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("authoringSurface", "STUDIO");
        metadata.put("structureOperation", structureOperation);
        metadata.put("expectedRevision", expectedRevision);
        if (parentBlockId == null) {
            metadata.putNull("parentBlockId");
        } else {
            metadata.put("parentBlockId", parentBlockId);
        }
        if (beforeBlockId == null) {
            metadata.putNull("beforeBlockId");
        } else {
            metadata.put("beforeBlockId", beforeBlockId);
        }
        if (changeItemPid != null) {
            metadata.put("changeItemPid", changeItemPid);
            metadata.put("resultRevision", expectedRevision + 1);
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
