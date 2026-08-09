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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthoringIdentitySimulationServiceTest {

    @Mock
    private AuthoringWorkspaceService workspaceService;
    @Mock
    private AuthoringRoleStructurePreviewService roleStructurePreviewService;
    @Mock
    private AuthoringIdentitySimulationRepository simulationRepository;
    @Mock
    private AuthoringWorkspaceRepository workspaceRepository;

    private AuthoringIdentitySimulationService service;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(7L, 11L, "user-11", "security-admin");
        MetaContext.setEnvironmentId(1L);
        service = new AuthoringIdentitySimulationService(
                workspaceService,
                roleStructurePreviewService,
                simulationRepository,
                workspaceRepository,
                new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void startsActorBoundReadonlySimulationAndAuditsWithoutReasonPayload() throws Exception {
        SessionView session = session();
        RoleStructurePreviewView structure = structure();
        when(workspaceService.get("session-1")).thenReturn(session);
        when(roleStructurePreviewService.preview("session-1", "role-operator"))
                .thenReturn(structure);

        IdentitySimulationView view = service.start(
                "session-1",
                new StartIdentitySimulationRequest("role-operator", 5, "  incident review  "));

        assertThat(view.mode()).isEqualTo("AUDITED_IDENTITY");
        assertThat(view.status()).isEqualTo("ACTIVE");
        assertThat(view.actorIntersectionApplied()).isTrue();
        assertThat(view.businessDataIncluded()).isFalse();
        assertThat(view.readOnly()).isTrue();
        assertThat(view.exportAllowed()).isFalse();
        assertThat(view.businessActionsAllowed()).isFalse();
        assertThat(Duration.between(view.startedAt(), view.expiresAt()).toMinutes()).isEqualTo(5);

        ArgumentCaptor<CreateSimulation> created = ArgumentCaptor.forClass(CreateSimulation.class);
        verify(simulationRepository).create(created.capture());
        assertThat(created.getValue())
                .extracting(
                        CreateSimulation::tenantId,
                        CreateSimulation::envId,
                        CreateSimulation::actorUserId,
                        CreateSimulation::sourceSessionPid,
                        CreateSimulation::changeSetPid,
                        CreateSimulation::targetRolePid,
                        CreateSimulation::reason)
                .containsExactly(
                        7L, 1L, 11L, "session-1", "changes-1", "role-operator",
                        "incident review");
        ArgumentCaptor<AuditEntry> audit = ArgumentCaptor.forClass(AuditEntry.class);
        verify(workspaceRepository).audit(audit.capture());
        assertThat(audit.getValue().eventType()).isEqualTo("IDENTITY_SIMULATION_STARTED");
        assertThat(audit.getValue().metadata().toString())
                .contains("role-operator")
                .doesNotContain("incident review");
    }

    @Test
    void activeAccessReevaluatesIntersectionAndAppendsAccessAudit() {
        SimulationRow row = activeRow(Instant.now().plusSeconds(300));
        when(simulationRepository.find(7L, 1L, 11L, "simulation-1", true)).thenReturn(row);
        when(roleStructurePreviewService.preview("session-1", "role-operator"))
                .thenReturn(structure());
        when(simulationRepository.markAccessed(any(), any())).thenReturn(true);

        IdentitySimulationView view = service.get("simulation-1");

        assertThat(view.status()).isEqualTo("ACTIVE");
        assertThat(view.decisions()).hasSize(1);
        verify(roleStructurePreviewService).preview("session-1", "role-operator");
        verify(simulationRepository).markAccessed(any(), any());
        ArgumentCaptor<AuditEntry> audit = ArgumentCaptor.forClass(AuditEntry.class);
        verify(workspaceRepository).audit(audit.capture());
        assertThat(audit.getValue().eventType()).isEqualTo("IDENTITY_SIMULATION_ACCESSED");
    }

    @Test
    void expiredAndForeignSessionsFailClosedWithoutReevaluatingRole() {
        SimulationRow expired = activeRow(Instant.now().minusSeconds(1));
        when(simulationRepository.find(7L, 1L, 11L, "expired", true)).thenReturn(expired);
        when(simulationRepository.end(eq(expired), eq("EXPIRED"), any())).thenReturn(true);

        IdentitySimulationView terminal = service.get("expired");

        assertThat(terminal.status()).isEqualTo("EXPIRED");
        assertThat(terminal.decisions()).isEmpty();
        verify(roleStructurePreviewService, never()).preview(any(), any());

        when(simulationRepository.find(7L, 1L, 11L, "foreign", true)).thenReturn(null);
        assertThatThrownBy(() -> service.get("foreign"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND")
                .hasMessageContaining("authoring.identity-simulation.not-found");
    }

    private SessionView session() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        return new SessionView(
                "session-1",
                "changes-1",
                "page-1",
                null,
                11L,
                "DRAFT",
                "AUTHORING",
                "ACTIVE",
                3L,
                "LOW",
                "HANDOFF_STUDIO",
                "DRAFT",
                "UNKNOWN",
                null,
                "UNKNOWN",
                null,
                "NOT_REQUIRED",
                "NOT_PUBLISHED",
                "manifest",
                objectMapper.readTree("{\"schemaVersion\":3}"),
                objectMapper.createObjectNode(),
                null,
                Instant.now().plusSeconds(600));
    }

    private RoleStructurePreviewView structure() {
        return new RoleStructurePreviewView(
                "STRUCTURE",
                "page-1",
                new RolePreviewTargetView("role-operator", "operator", "Operator"),
                true,
                false,
                false,
                false,
                List.of(new AuthoringWorkspaceContracts.RoleStructureDecisionView(
                        "FIELD", "name", "Name", "customer.read",
                        true, true, false, "ALLOW")));
    }

    private SimulationRow activeRow(Instant expiresAt) {
        Instant startedAt = expiresAt.minusSeconds(300);
        return new SimulationRow(
                1L,
                "simulation-1",
                7L,
                1L,
                11L,
                "session-1",
                "changes-1",
                "page-1",
                "role-operator",
                "operator",
                "Operator",
                "incident review",
                "ACTIVE",
                startedAt,
                expiresAt,
                null,
                null,
                1L);
    }
}
