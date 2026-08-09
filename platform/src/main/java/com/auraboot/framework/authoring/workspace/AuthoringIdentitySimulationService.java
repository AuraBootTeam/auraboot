package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.authoring.workspace.AuthoringIdentitySimulationRepository.CreateSimulation;
import com.auraboot.framework.authoring.workspace.AuthoringIdentitySimulationRepository.SimulationRow;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.IdentitySimulationView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.RolePreviewTargetView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.RoleStructurePreviewView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.SessionView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.StartIdentitySimulationRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceRepository.AuditEntry;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/** Lifecycle and append-only audit for actor-bound, read-only identity simulations. */
@Service
public class AuthoringIdentitySimulationService {

    private static final String MODE = "AUDITED_IDENTITY";

    private final AuthoringWorkspaceService workspaceService;
    private final AuthoringRoleStructurePreviewService roleStructurePreviewService;
    private final AuthoringIdentitySimulationRepository simulationRepository;
    private final AuthoringWorkspaceRepository workspaceRepository;
    private final ObjectMapper objectMapper;

    public AuthoringIdentitySimulationService(
            AuthoringWorkspaceService workspaceService,
            AuthoringRoleStructurePreviewService roleStructurePreviewService,
            AuthoringIdentitySimulationRepository simulationRepository,
            AuthoringWorkspaceRepository workspaceRepository,
            ObjectMapper objectMapper) {
        this.workspaceService = workspaceService;
        this.roleStructurePreviewService = roleStructurePreviewService;
        this.simulationRepository = simulationRepository;
        this.workspaceRepository = workspaceRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public IdentitySimulationView start(
            String sourceSessionPid,
            StartIdentitySimulationRequest request) {
        SessionView session = workspaceService.get(sourceSessionPid);
        RoleStructurePreviewView structure = roleStructurePreviewService.preview(
                sourceSessionPid, request.rolePid());
        Identity identity = identity();
        Instant startedAt = Instant.now();
        Instant expiresAt = startedAt.plus(Duration.ofMinutes(request.durationMinutes()));
        String simulationPid = UniqueIdGenerator.generate();
        simulationRepository.create(new CreateSimulation(
                simulationPid,
                identity.tenantId(),
                identity.envId(),
                identity.actorUserId(),
                sourceSessionPid,
                session.changeSetPid(),
                session.pagePid(),
                structure.targetRole().rolePid(),
                structure.targetRole().roleCode(),
                structure.targetRole().roleName(),
                request.reason().trim(),
                startedAt,
                expiresAt));
        audit(
                identity,
                session,
                simulationPid,
                structure.targetRole().rolePid(),
                "IDENTITY_SIMULATION_STARTED",
                "STARTED",
                request.durationMinutes());
        return view(
                simulationPid,
                sourceSessionPid,
                session.pagePid(),
                structure.targetRole(),
                "ACTIVE",
                startedAt,
                expiresAt,
                null,
                structure.decisions());
    }

    @Transactional
    public IdentitySimulationView get(String simulationPid) {
        Identity identity = identity();
        SimulationRow row = requireRow(identity, simulationPid, true);
        Instant now = Instant.now();
        if ("ACTIVE".equals(row.status()) && !now.isBefore(row.expiresAt())) {
            simulationRepository.end(row, "EXPIRED", now);
            audit(
                    identity,
                    row,
                    "IDENTITY_SIMULATION_EXPIRED",
                    "TTL_EXPIRED",
                    durationMinutes(row));
            return terminalView(row, "EXPIRED", now);
        }
        if (!"ACTIVE".equals(row.status())) {
            return terminalView(row, row.status(), row.endedAt());
        }

        RoleStructurePreviewView structure = roleStructurePreviewService.preview(
                row.sourceSessionPid(), row.targetRolePid());
        if (!simulationRepository.markAccessed(row, now)) {
            throw new ResponseStatusException(NOT_FOUND, "authoring.identity-simulation.stale");
        }
        audit(
                identity,
                row,
                "IDENTITY_SIMULATION_ACCESSED",
                "READ_ONLY_VIEW",
                durationMinutes(row));
        return view(
                row.pid(),
                row.sourceSessionPid(),
                row.pagePid(),
                structure.targetRole(),
                "ACTIVE",
                row.startedAt(),
                row.expiresAt(),
                null,
                structure.decisions());
    }

    @Transactional
    public IdentitySimulationView end(String simulationPid) {
        Identity identity = identity();
        SimulationRow row = requireRow(identity, simulationPid, true);
        if (!"ACTIVE".equals(row.status())) {
            return terminalView(row, row.status(), row.endedAt());
        }
        Instant now = Instant.now();
        String status = now.isBefore(row.expiresAt()) ? "ENDED" : "EXPIRED";
        if (!simulationRepository.end(row, status, now)) {
            throw new ResponseStatusException(NOT_FOUND, "authoring.identity-simulation.stale");
        }
        audit(
                identity,
                row,
                "EXPIRED".equals(status)
                        ? "IDENTITY_SIMULATION_EXPIRED"
                        : "IDENTITY_SIMULATION_ENDED",
                "EXPIRED".equals(status) ? "TTL_EXPIRED" : "USER_ENDED",
                durationMinutes(row));
        return terminalView(row, status, now);
    }

    private SimulationRow requireRow(Identity identity, String simulationPid, boolean lock) {
        SimulationRow row = simulationRepository.find(
                identity.tenantId(),
                identity.envId(),
                identity.actorUserId(),
                simulationPid,
                lock);
        if (row == null) {
            throw new ResponseStatusException(NOT_FOUND, "authoring.identity-simulation.not-found");
        }
        return row;
    }

    private IdentitySimulationView terminalView(
            SimulationRow row,
            String status,
            Instant endedAt) {
        return view(
                row.pid(),
                row.sourceSessionPid(),
                row.pagePid(),
                new RolePreviewTargetView(
                        row.targetRolePid(), row.targetRoleCode(), row.targetRoleName()),
                status,
                row.startedAt(),
                row.expiresAt(),
                endedAt,
                List.of());
    }

    private static IdentitySimulationView view(
            String simulationPid,
            String sourceSessionPid,
            String pagePid,
            RolePreviewTargetView targetRole,
            String status,
            Instant startedAt,
            Instant expiresAt,
            Instant endedAt,
            List<AuthoringWorkspaceContracts.RoleStructureDecisionView> decisions) {
        return new IdentitySimulationView(
                simulationPid,
                MODE,
                sourceSessionPid,
                pagePid,
                targetRole,
                true,
                false,
                true,
                false,
                false,
                status,
                startedAt,
                expiresAt,
                endedAt,
                decisions);
    }

    private void audit(
            Identity identity,
            SessionView session,
            String simulationPid,
            String targetRolePid,
            String eventType,
            String reasonCode,
            int durationMinutes) {
        audit(
                identity,
                session.changeSetPid(),
                session.sessionPid(),
                session.pagePid(),
                simulationPid,
                targetRolePid,
                eventType,
                reasonCode,
                durationMinutes);
    }

    private void audit(
            Identity identity,
            SimulationRow row,
            String eventType,
            String reasonCode,
            int durationMinutes) {
        audit(
                identity,
                row.changeSetPid(),
                row.sourceSessionPid(),
                row.pagePid(),
                row.pid(),
                row.targetRolePid(),
                eventType,
                reasonCode,
                durationMinutes);
    }

    private void audit(
            Identity identity,
            String changeSetPid,
            String sessionPid,
            String pagePid,
            String simulationPid,
            String targetRolePid,
            String eventType,
            String reasonCode,
            int durationMinutes) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("simulationPid", simulationPid);
        metadata.put("targetRolePid", targetRolePid);
        metadata.put("durationMinutes", durationMinutes);
        metadata.put("readOnly", true);
        metadata.put("exportAllowed", false);
        metadata.put("businessDataIncluded", false);
        workspaceRepository.audit(new AuditEntry(
                UniqueIdGenerator.generate(),
                identity.tenantId(),
                identity.envId(),
                identity.actorUserId(),
                changeSetPid,
                sessionPid,
                eventType,
                "ALLOW",
                reasonCode,
                "PAGE_SCHEMA",
                pagePid,
                null,
                null,
                MetaContext.getOtelTraceId(),
                metadata));
    }

    private static int durationMinutes(SimulationRow row) {
        return Math.toIntExact(Duration.between(row.startedAt(), row.expiresAt()).toMinutes());
    }

    private static Identity identity() {
        return new Identity(
                MetaContext.getCurrentTenantId(),
                MetaContext.getCurrentEnvironmentId(),
                MetaContext.getCurrentUserId());
    }

    private record Identity(long tenantId, long envId, long actorUserId) {
    }
}
