package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.CreateHandoffRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.HandoffContextView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.HandoffCreatedView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceRepository.AuditEntry;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceRepository.CreateHandoff;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceRepository.HandoffRow;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceRepository.WorkspaceRow;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.GONE;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/** Creates and consumes short-lived, actor-bound Studio handoff contexts. */
@Service
public class AuthoringHandoffService {

    private static final Duration HANDOFF_TTL = Duration.ofMinutes(10);
    private static final String PAGE_DESIGNER_ROUTE = "/unified-designer";

    private final AuthoringWorkspaceRepository repository;
    private final ObjectMapper objectMapper;
    private final AuthoringHandoffTokenCodec tokenCodec;
    private final AuthoringHandoffContextMapper contextMapper;

    public AuthoringHandoffService(
            AuthoringWorkspaceRepository repository,
            ObjectMapper objectMapper,
            AuthoringHandoffTokenCodec tokenCodec,
            AuthoringHandoffContextMapper contextMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.tokenCodec = tokenCodec;
        this.contextMapper = contextMapper;
    }

    @Transactional
    public HandoffCreatedView create(String sessionPid, CreateHandoffRequest request) {
        Identity identity = identity();
        WorkspaceRow workspace = requireWorkspace(identity, sessionPid, true);
        validateSource(workspace, request.expectedRevision());

        String contextId = tokenCodec.create();
        Instant expiresAt = Instant.now().plus(HANDOFF_TTL);
        JsonNode payload = contextMapper.createPayload(workspace, request);
        repository.createHandoff(new CreateHandoff(
                UniqueIdGenerator.generate(), identity.tenantId(), identity.envId(), identity.userId(),
                workspace.changeSetId(), tokenCodec.hash(contextId), PAGE_DESIGNER_ROUTE,
                payload, expiresAt));
        repository.audit(audit(identity, workspace.changeSetPid(), workspace.sessionPid(),
                "HANDOFF_CREATED", request.intent().name(), workspace.pagePid(),
                request.blockId(), request.propertyPath(), objectMapper.valueToTree(Map.of(
                        "targetRoute", PAGE_DESIGNER_ROUTE,
                        "expiresAt", expiresAt.toString()))));
        return new HandoffCreatedView(contextId, PAGE_DESIGNER_ROUTE, expiresAt);
    }

    @Transactional
    public HandoffContextView consume(String contextId) {
        Identity identity = identity();
        if (!tokenCodec.isValid(contextId)) {
            throw new ResponseStatusException(NOT_FOUND, "authoring.handoff.not-found");
        }
        HandoffRow handoff = repository.findHandoff(
                identity.tenantId(), identity.envId(), identity.userId(),
                tokenCodec.hash(contextId), true);
        if (handoff == null) {
            throw new ResponseStatusException(NOT_FOUND, "authoring.handoff.not-found");
        }
        if (handoff.consumedAt() != null) {
            throw new ResponseStatusException(CONFLICT, "authoring.handoff.consumed");
        }
        if (!handoff.expiresAt().isAfter(Instant.now())) {
            throw new ResponseStatusException(GONE, "authoring.handoff.expired");
        }
        if (!PAGE_DESIGNER_ROUTE.equals(handoff.targetRoute()) || !repository.consumeHandoff(handoff)) {
            throw new ResponseStatusException(CONFLICT, "authoring.handoff.consumed");
        }

        JsonNode payload = handoff.contextPayload();
        String sessionPid = payload.path("sessionPid").asText();
        requireWorkspace(identity, sessionPid, false);
        repository.audit(audit(identity, handoff.changeSetPid(), sessionPid,
                "HANDOFF_CONSUMED", payload.path("intent").asText(),
                payload.path("pagePid").asText(), contextMapper.nullableText(payload, "blockId"),
                contextMapper.nullableText(payload, "propertyPath"), objectMapper.valueToTree(Map.of(
                        "targetRoute", handoff.targetRoute()))));
        return contextMapper.toView(handoff);
    }

    private WorkspaceRow requireWorkspace(Identity identity, String sessionPid, boolean lock) {
        WorkspaceRow workspace = repository.find(
                identity.tenantId(), identity.envId(), sessionPid, lock);
        if (workspace == null) {
            throw new ResponseStatusException(NOT_FOUND, "authoring.session.not-found");
        }
        if (workspace.actorUserId() != identity.userId()) {
            throw new ResponseStatusException(FORBIDDEN, "authoring.session.actor-mismatch");
        }
        return workspace;
    }

    private void validateSource(WorkspaceRow workspace, long expectedRevision) {
        if ("REVIEW".equals(workspace.workspaceMode())) {
            throw new ResponseStatusException(FORBIDDEN, "authoring.review.workspace-read-only");
        }
        if (!"ACTIVE".equals(workspace.sessionState())
                || !workspace.expiresAt().isAfter(Instant.now())) {
            throw new ResponseStatusException(CONFLICT, "authoring.session.expired");
        }
        if (workspace.changeSetRevision() != expectedRevision
                || workspace.resourceRevision() != expectedRevision
                || workspace.sessionRevision() != expectedRevision) {
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

    private AuditEntry audit(
            Identity identity,
            String changeSetPid,
            String sessionPid,
            String eventType,
            String reasonCode,
            String resourcePid,
            String blockId,
            String propertyPath,
            JsonNode metadata) {
        return new AuditEntry(
                UniqueIdGenerator.generate(), identity.tenantId(), identity.envId(),
                identity.userId(), changeSetPid, sessionPid, eventType, "ALLOW", reasonCode,
                "PAGE_SCHEMA", resourcePid, blockId, propertyPath, MetaContext.getOtelTraceId(),
                metadata == null ? objectMapper.createObjectNode() : metadata);
    }

    private record Identity(long tenantId, long envId, long userId) {
    }
}
