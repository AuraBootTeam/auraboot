package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.authoring.policy.AuthoringCapabilityRegistry;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.BoundaryDecision;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.PatchOperation;
import com.auraboot.framework.authoring.workspace.AuthoringAiPatchProposalRepository.CreateProposal;
import com.auraboot.framework.authoring.workspace.AuthoringAiPatchProposalRepository.ProposalRow;
import com.auraboot.framework.authoring.workspace.AuthoringPatchEngine.PreparedPatch;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.AiPatchProposalItemRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.AiPatchProposalItemView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.AiPatchProposalView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ApplyAiPatchProposalRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ApplyAiPatchProposalResult;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.CreateAiPatchProposalRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.RejectAiPatchProposalRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.SessionView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceRepository.AggregatePolicy;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceRepository.AuditEntry;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceRepository.WorkspaceRow;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;

/**
 * Converts untrusted AI output into a durable proposal and applies it only after a human confirms.
 * Proposal and apply both execute the same Studio patch policy, sanitization and revision checks.
 */
@Service
public class AuthoringAiPatchProposalService {

    private static final int MAX_SERIALIZED_ITEMS_BYTES = 64 * 1024;
    private static final Duration WRITER_LEASE = Duration.ofMinutes(5);
    private static final String PROPOSED = "PROPOSED";
    private static final String APPLIED = "APPLIED";
    private static final String REJECTED = "REJECTED";
    private static final String REVIEW_WORKSPACE_MODE = "REVIEW";

    private final AuthoringCapabilityRegistry capabilityRegistry;
    private final AuthoringPatchEngine patchEngine;
    private final AuthoringWorkspaceRepository workspaceRepository;
    private final AuthoringAiPatchProposalRepository proposalRepository;
    private final AuthoringPageSnapshotFactory snapshotFactory;
    private final AuthoringAggregatePolicyService aggregatePolicyService;
    private final AuthoringWorkspaceViewMapper viewMapper;
    private final AuthoringAuditService auditService;
    private final ObjectMapper objectMapper;

    public AuthoringAiPatchProposalService(
            AuthoringCapabilityRegistry capabilityRegistry,
            AuthoringPatchEngine patchEngine,
            AuthoringWorkspaceRepository workspaceRepository,
            AuthoringAiPatchProposalRepository proposalRepository,
            AuthoringPageSnapshotFactory snapshotFactory,
            AuthoringAggregatePolicyService aggregatePolicyService,
            AuthoringWorkspaceViewMapper viewMapper,
            AuthoringAuditService auditService,
            ObjectMapper objectMapper) {
        this.capabilityRegistry = capabilityRegistry;
        this.patchEngine = patchEngine;
        this.workspaceRepository = workspaceRepository;
        this.proposalRepository = proposalRepository;
        this.snapshotFactory = snapshotFactory;
        this.aggregatePolicyService = aggregatePolicyService;
        this.viewMapper = viewMapper;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AiPatchProposalView create(
            String sessionPid,
            CreateAiPatchProposalRequest request) {
        Identity identity = identity();
        WorkspaceRow workspace = requireWorkspace(identity, sessionPid, true);
        try {
            validateWritable(workspace, identity, request.expectedRevision());
            ProposalComputation computation = compute(workspace, request.items());
            String proposalPid = UniqueIdGenerator.generate();
            String proposalHash = snapshotFactory.checksum(computation.items());
            AggregatePolicy aggregate = aggregatePolicyService.aggregate(
                    workspace, computation.decisions());
            proposalRepository.create(new CreateProposal(
                    proposalPid,
                    identity.tenantId(),
                    identity.envId(),
                    identity.userId(),
                    workspace.sessionId(),
                    workspace.sessionPid(),
                    workspace.changeSetId(),
                    workspace.changeSetPid(),
                    workspace.pagePid(),
                    workspace.changeSetRevision(),
                    capabilityRegistry.checksum(),
                    proposalHash,
                    computation.items().size(),
                    computation.items(),
                    objectMapper.valueToTree(computation.decisions()),
                    aggregate.riskLevel(),
                    aggregate.route(),
                    aggregate.publishPolicy()));
            auditCreated(identity, workspace, proposalPid, proposalHash, computation, aggregate);
            return toView(requireProposal(identity, sessionPid, proposalPid, false));
        } catch (ResponseStatusException exception) {
            auditDenied(identity, workspace, "AI_PATCH_PROPOSAL_DENIED", exception,
                    request.expectedRevision(), request.items().size(), null);
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public AiPatchProposalView get(String sessionPid, String proposalPid) {
        Identity identity = identity();
        requireWorkspace(identity, sessionPid, false);
        return toView(requireProposal(identity, sessionPid, proposalPid, false));
    }

    @Transactional
    public ApplyAiPatchProposalResult apply(
            String sessionPid,
            String proposalPid,
            ApplyAiPatchProposalRequest request) {
        Identity identity = identity();
        WorkspaceRow workspace = requireWorkspace(identity, sessionPid, true);
        ProposalRow proposal = requireProposal(identity, sessionPid, proposalPid, true);
        try {
            validateApplicable(workspace, identity, proposal, request.expectedRevision());
            List<StoredProposalItem> storedItems = storedItems(proposal.items());
            List<AiPatchProposalItemRequest> items = requests(storedItems);
            ProposalComputation computation = compute(workspace, items);
            verifyUnchangedProposal(proposal, computation);

            WorkspaceRow current = workspace;
            for (int index = 0; index < computation.prepared().size(); index++) {
                PreparedPatch prepared = computation.prepared().get(index);
                AiPatchProposalItemRequest item = items.get(index);
                String changeItemPid = UniqueIdGenerator.generate();
                AggregatePolicy aggregate = aggregatePolicyService.aggregate(
                        current, prepared.decision());
                workspaceRepository.persistPatch(
                        current,
                        prepared.snapshot(),
                        capabilityRegistry.checksum(),
                        changeItemPid,
                        item.blockId(),
                        item.propertyPath(),
                        item.operation().name(),
                        prepared.previousValue(),
                        prepared.savedValue(),
                        prepared.capability(),
                        prepared.decision(),
                        identity.userId(),
                        aggregate,
                        Instant.now().plus(WRITER_LEASE));
                auditItemApplied(
                        identity, current, proposal, item, prepared, changeItemPid, index + 1);
                current = requireWorkspace(identity, sessionPid, true);
            }

            Instant appliedAt = Instant.now();
            if (!proposalRepository.markApplied(
                    proposal, current.changeSetRevision(), appliedAt)) {
                throw new ResponseStatusException(CONFLICT, "authoring.ai-proposal.stale");
            }
            auditApplied(identity, current, proposal, computation.items().size());
            ProposalRow applied = requireProposal(identity, sessionPid, proposalPid, false);
            SessionView session = viewMapper.toView(current, identity.userId());
            return new ApplyAiPatchProposalResult(toView(applied), session);
        } catch (ResponseStatusException exception) {
            auditDenied(identity, workspace, "AI_PATCH_PROPOSAL_APPLY_DENIED", exception,
                    request.expectedRevision(), proposal.itemCount(), proposal.pid());
            throw exception;
        }
    }

    @Transactional
    public AiPatchProposalView reject(
            String sessionPid,
            String proposalPid,
            RejectAiPatchProposalRequest request) {
        Identity identity = identity();
        WorkspaceRow workspace = requireWorkspace(identity, sessionPid, false);
        ProposalRow proposal = requireProposal(identity, sessionPid, proposalPid, true);
        if (!PROPOSED.equals(proposal.status())) {
            throw new ResponseStatusException(CONFLICT, "authoring.ai-proposal.not-proposed");
        }
        Instant rejectedAt = Instant.now();
        if (!proposalRepository.markRejected(proposal, request.reason().trim(), rejectedAt)) {
            throw new ResponseStatusException(CONFLICT, "authoring.ai-proposal.stale");
        }
        ObjectNode metadata = proposalMetadata(proposal);
        metadata.put("itemCount", proposal.itemCount());
        workspaceRepository.audit(audit(
                identity, workspace, "AI_PATCH_PROPOSAL_REJECTED", "ALLOW",
                "HUMAN_REJECTED", metadata));
        return toView(requireProposal(identity, sessionPid, proposalPid, false));
    }

    private ProposalComputation compute(
            WorkspaceRow workspace,
            List<AiPatchProposalItemRequest> requestedItems) {
        ArrayNode normalizedItems = objectMapper.createArrayNode();
        List<BoundaryDecision> decisions = new ArrayList<>();
        List<PreparedPatch> preparedPatches = new ArrayList<>();
        Set<String> targets = new HashSet<>();
        JsonNode candidate = workspace.snapshot().deepCopy();

        for (AiPatchProposalItemRequest item : requestedItems) {
            requirePropertyOperation(item.operation());
            String target = item.blockId() + '\n' + item.propertyPath();
            if (!targets.add(target)) {
                throw new ResponseStatusException(
                        UNPROCESSABLE_ENTITY, "authoring.ai-proposal.duplicate-target");
            }
            PreparedPatch prepared = patchEngine.prepareStudio(
                    candidate,
                    item.blockId(),
                    item.propertyPath(),
                    item.operation(),
                    item.value(),
                    item.manifestChecksum(),
                    snapshotFactory.resourceScope(candidate));
            StoredProposalItem normalized = new StoredProposalItem(
                    item.blockId(),
                    item.propertyPath(),
                    item.operation(),
                    prepared.previousValue(),
                    prepared.savedValue(),
                    prepared.decision().manifestChecksum());
            normalizedItems.add(objectMapper.valueToTree(normalized));
            decisions.add(prepared.decision());
            preparedPatches.add(prepared);
            candidate = prepared.snapshot();
        }
        int serializedSize = normalizedItems.toString()
                .getBytes(StandardCharsets.UTF_8).length;
        if (serializedSize > MAX_SERIALIZED_ITEMS_BYTES) {
            throw new ResponseStatusException(
                    UNPROCESSABLE_ENTITY, "authoring.ai-proposal.payload-too-large");
        }
        return new ProposalComputation(normalizedItems, decisions, preparedPatches);
    }

    private void verifyUnchangedProposal(
            ProposalRow proposal,
            ProposalComputation computation) {
        if (!capabilityRegistry.checksum().equals(proposal.registryChecksum())
                || !proposal.proposalHash().equals(snapshotFactory.checksum(computation.items()))
                || !decisions(proposal.decisions()).equals(computation.decisions())) {
            throw new ResponseStatusException(CONFLICT, "authoring.ai-proposal.policy-stale");
        }
    }

    private void validateApplicable(
            WorkspaceRow workspace,
            Identity identity,
            ProposalRow proposal,
            long expectedRevision) {
        validateWritable(workspace, identity, expectedRevision);
        if (!PROPOSED.equals(proposal.status())) {
            throw new ResponseStatusException(CONFLICT, "authoring.ai-proposal.not-proposed");
        }
        if (proposal.sourceSessionId() != workspace.sessionId()
                || proposal.changeSetId() != workspace.changeSetId()
                || proposal.baseRevision() != expectedRevision) {
            throw new ResponseStatusException(CONFLICT, "authoring.ai-proposal.revision-stale");
        }
    }

    private void validateWritable(
            WorkspaceRow workspace,
            Identity identity,
            long expectedRevision) {
        if (REVIEW_WORKSPACE_MODE.equals(workspace.workspaceMode())) {
            throw new ResponseStatusException(FORBIDDEN, "authoring.review.workspace-read-only");
        }
        Instant now = Instant.now();
        if (!workspace.expiresAt().isAfter(now)) {
            throw new ResponseStatusException(CONFLICT, "authoring.session.expired");
        }
        if (workspace.leaseSessionId() != workspace.sessionId()
                || workspace.leaseHolderUserId() != identity.userId()) {
            throw new ResponseStatusException(CONFLICT, "authoring.writer-lease.lost");
        }
        if (!"ACTIVE".equals(workspace.sessionState())) {
            throw new ResponseStatusException(CONFLICT, "authoring.session.read-only");
        }
        if (!workspace.leasedUntil().isAfter(now)) {
            throw new ResponseStatusException(CONFLICT, "authoring.writer-lease.expired");
        }
        if (workspace.changeSetRevision() != expectedRevision
                || workspace.resourceRevision() != expectedRevision
                || workspace.sessionRevision() != expectedRevision) {
            throw new ResponseStatusException(CONFLICT, "authoring.revision.conflict");
        }
    }

    private WorkspaceRow requireWorkspace(Identity identity, String sessionPid, boolean lock) {
        WorkspaceRow workspace = workspaceRepository.find(
                identity.tenantId(), identity.envId(), sessionPid, lock);
        if (workspace == null) {
            throw new ResponseStatusException(NOT_FOUND, "authoring.session.not-found");
        }
        if (workspace.actorUserId() != identity.userId()) {
            throw new ResponseStatusException(FORBIDDEN, "authoring.session.actor-mismatch");
        }
        return workspace;
    }

    private ProposalRow requireProposal(
            Identity identity,
            String sessionPid,
            String proposalPid,
            boolean lock) {
        ProposalRow proposal = proposalRepository.find(
                identity.tenantId(), identity.envId(), identity.userId(),
                sessionPid, proposalPid, lock);
        if (proposal == null) {
            throw new ResponseStatusException(NOT_FOUND, "authoring.ai-proposal.not-found");
        }
        return proposal;
    }

    private void requirePropertyOperation(PatchOperation operation) {
        if (operation == PatchOperation.MOVE || operation == PatchOperation.COPY) {
            throw new ResponseStatusException(
                    UNPROCESSABLE_ENTITY, "authoring.ai-proposal.property-patch-required");
        }
    }

    private List<StoredProposalItem> storedItems(JsonNode value) {
        return objectMapper.convertValue(
                value, new TypeReference<List<StoredProposalItem>>() { });
    }

    private List<AiPatchProposalItemRequest> requests(List<StoredProposalItem> items) {
        return items.stream()
                .map(item -> new AiPatchProposalItemRequest(
                        item.blockId(),
                        item.propertyPath(),
                        item.operation(),
                        item.value(),
                        item.manifestChecksum()))
                .toList();
    }

    private List<BoundaryDecision> decisions(JsonNode value) {
        return objectMapper.convertValue(
                value, new TypeReference<List<BoundaryDecision>>() { });
    }

    private AiPatchProposalView toView(ProposalRow proposal) {
        List<StoredProposalItem> items = storedItems(proposal.items());
        List<BoundaryDecision> decisions = decisions(proposal.decisions());
        List<AiPatchProposalItemView> itemViews = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            StoredProposalItem item = items.get(index);
            itemViews.add(new AiPatchProposalItemView(
                    index + 1,
                    item.blockId(),
                    item.propertyPath(),
                    item.operation(),
                    item.previousValue(),
                    item.value(),
                    item.manifestChecksum(),
                    decisions.get(index)));
        }
        return new AiPatchProposalView(
                proposal.pid(),
                proposal.sourceSessionPid(),
                proposal.changeSetPid(),
                proposal.pagePid(),
                proposal.baseRevision(),
                proposal.registryChecksum(),
                proposal.proposalHash(),
                proposal.status(),
                proposal.aggregateRisk(),
                proposal.aggregateRoute(),
                proposal.publishPolicy(),
                true,
                true,
                List.copyOf(itemViews),
                proposal.resultRevision(),
                proposal.createdAt(),
                proposal.appliedAt(),
                proposal.rejectedAt());
    }

    private void auditCreated(
            Identity identity,
            WorkspaceRow workspace,
            String proposalPid,
            String proposalHash,
            ProposalComputation computation,
            AggregatePolicy aggregate) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("proposalPid", proposalPid);
        metadata.put("proposalHash", proposalHash);
        metadata.put("itemCount", computation.items().size());
        metadata.put("baseRevision", workspace.changeSetRevision());
        metadata.put("aggregateRisk", aggregate.riskLevel());
        metadata.put("aggregateRoute", aggregate.route());
        metadata.put("publishPolicy", aggregate.publishPolicy());
        metadata.put("typedPatchOnly", true);
        metadata.put("requiresHumanApproval", true);
        workspaceRepository.audit(audit(
                identity, workspace, "AI_PATCH_PROPOSAL_CREATED", "ALLOW",
                "SERVER_VALIDATED", metadata));
    }

    private void auditItemApplied(
            Identity identity,
            WorkspaceRow workspace,
            ProposalRow proposal,
            AiPatchProposalItemRequest item,
            PreparedPatch prepared,
            String changeItemPid,
            int ordinal) {
        ObjectNode metadata = proposalMetadata(proposal);
        metadata.put("ordinal", ordinal);
        metadata.put("changeItemPid", changeItemPid);
        metadata.put("operation", item.operation().name());
        metadata.put("resultRevision", workspace.changeSetRevision() + 1);
        metadata.put("riskLevel", prepared.decision().risk().name());
        metadata.put("route", prepared.decision().route().name());
        workspaceRepository.audit(new AuditEntry(
                UniqueIdGenerator.generate(),
                identity.tenantId(),
                identity.envId(),
                identity.userId(),
                workspace.changeSetPid(),
                workspace.sessionPid(),
                "AI_PATCH_PROPOSAL_ITEM_APPLIED",
                "ALLOW",
                prepared.decision().reason().name(),
                "PAGE_SCHEMA",
                workspace.pagePid(),
                item.blockId(),
                item.propertyPath(),
                MetaContext.getOtelTraceId(),
                metadata));
    }

    private void auditApplied(
            Identity identity,
            WorkspaceRow workspace,
            ProposalRow proposal,
            int itemCount) {
        ObjectNode metadata = proposalMetadata(proposal);
        metadata.put("itemCount", itemCount);
        metadata.put("baseRevision", proposal.baseRevision());
        metadata.put("resultRevision", workspace.changeSetRevision());
        workspaceRepository.audit(audit(
                identity, workspace, "AI_PATCH_PROPOSAL_APPLIED", "ALLOW",
                "HUMAN_CONFIRMED", metadata));
    }

    private void auditDenied(
            Identity identity,
            WorkspaceRow workspace,
            String eventType,
            ResponseStatusException exception,
            long expectedRevision,
            int itemCount,
            String proposalPid) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("expectedRevision", expectedRevision);
        metadata.put("itemCount", itemCount);
        metadata.put("typedPatchOnly", true);
        if (proposalPid != null) {
            metadata.put("proposalPid", proposalPid);
        }
        auditService.recordDenied(audit(
                identity, workspace, eventType, "DENY", reason(exception), metadata));
    }

    private AuditEntry audit(
            Identity identity,
            WorkspaceRow workspace,
            String eventType,
            String result,
            String reasonCode,
            JsonNode metadata) {
        return new AuditEntry(
                UniqueIdGenerator.generate(),
                identity.tenantId(),
                identity.envId(),
                identity.userId(),
                workspace.changeSetPid(),
                workspace.sessionPid(),
                eventType,
                result,
                reasonCode,
                "PAGE_SCHEMA",
                workspace.pagePid(),
                null,
                null,
                MetaContext.getOtelTraceId(),
                metadata);
    }

    private ObjectNode proposalMetadata(ProposalRow proposal) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("proposalPid", proposal.pid());
        metadata.put("proposalHash", proposal.proposalHash());
        return metadata;
    }

    private String reason(ResponseStatusException exception) {
        return exception.getReason() == null
                ? "AUTHORING_DENIED"
                : exception.getReason().toUpperCase(Locale.ROOT).replace('.', '_');
    }

    private static Identity identity() {
        MetaContext context = MetaContext.get();
        Long envId = MetaContext.getCurrentEnvironmentId();
        if (context.getTenantId() == null || context.getUserId() == null || envId == null) {
            throw new ResponseStatusException(FORBIDDEN, "authoring.context.incomplete");
        }
        return new Identity(context.getTenantId(), envId, context.getUserId());
    }

    private record ProposalComputation(
            ArrayNode items,
            List<BoundaryDecision> decisions,
            List<PreparedPatch> prepared) {
    }

    private record StoredProposalItem(
            String blockId,
            String propertyPath,
            PatchOperation operation,
            JsonNode previousValue,
            JsonNode value,
            String manifestChecksum) {
    }

    private record Identity(long tenantId, long envId, long userId) {
    }
}
