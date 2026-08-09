package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.RoleStructureDecisionView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.RoleStructurePreviewView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.SessionView;
import com.auraboot.framework.menu.entity.Menu;
import com.auraboot.framework.menu.mapper.MenuMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.permission.service.impl.PermissionSnapshotCache;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.service.RoleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthoringRoleStructurePreviewServiceTest {

    @Mock
    private AuthoringWorkspaceService workspaceService;
    @Mock
    private RoleService roleService;
    @Mock
    private UserPermissionService userPermissionService;
    @Mock
    private PermissionSnapshotCache permissionSnapshotCache;
    @Mock
    private MenuMapper menuMapper;

    private AuthoringRoleStructurePreviewService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        MetaContext.setContext(7L, 11L, "user-11", "designer");
        service = new AuthoringRoleStructurePreviewService(
                workspaceService,
                roleService,
                userPermissionService,
                permissionSnapshotCache,
                menuMapper);
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void previewIntersectsActorAndRoleAndReturnsStructureOnly() throws Exception {
        SessionView session = session("""
                {
                  "schemaVersion": 3,
                  "id": "customer-page",
                  "blocks": [
                    {"id":"public-field","blockType":"field","field":"name",
                     "props":{"label":"Name","permissionCode":"customer.public.read"}},
                    {"id":"secret-field","blockType":"field","field":"secret",
                     "props":{"label":"Secret","permissionCode":"customer.secret.read"}},
                    {"id":"delete-action","blockType":"action","actionType":"delete",
                     "props":{"label":"Delete","permissionCode":"customer.delete"}},
                    {"id":"summary","blockType":"markdown","props":{"title":"Summary"}}
                  ]
                }
                """);
        Role target = role(41L, "role-operator", "operator", "Operator", 20, "ACTIVE");
        Menu menu = new Menu();
        menu.setPid("menu-customer");
        menu.setCode("customer");
        menu.setName("Customers");
        menu.setPermissionCode("customer.secret.read");
        menu.setVisible(true);

        when(workspaceService.get("session-1")).thenReturn(session);
        when(roleService.findByPid("role-operator")).thenReturn(target);
        when(userPermissionService.getUserPermissionCodes(11L))
                .thenReturn(Set.of("customer.public.read"));
        when(permissionSnapshotCache.getEffectiveRolePermissionIds(7L, 41L))
                .thenReturn(Set.of(101L, 102L));
        when(permissionSnapshotCache.resolvePermissionCodes(7L, Set.of(101L, 102L)))
                .thenReturn(Set.of("customer.public.read", "customer.secret.read"));
        when(menuMapper.findActivePathByPage(7L, "page-1", null)).thenReturn(List.of(menu));

        RoleStructurePreviewView preview = service.preview("session-1", "role-operator");

        assertThat(preview.mode()).isEqualTo("STRUCTURE");
        assertThat(preview.actorIntersectionApplied()).isTrue();
        assertThat(preview.businessDataIncluded()).isFalse();
        assertThat(preview.exportAllowed()).isFalse();
        assertThat(preview.businessActionsAllowed()).isFalse();
        assertThat(decision(preview, "public-field"))
                .extracting(
                        RoleStructureDecisionView::nodeType,
                        RoleStructureDecisionView::allowed,
                        RoleStructureDecisionView::visible,
                        RoleStructureDecisionView::writable,
                        RoleStructureDecisionView::reason)
                .containsExactly("FIELD", true, true, true, "ALLOW");
        assertThat(decision(preview, "secret-field"))
                .extracting(RoleStructureDecisionView::allowed, RoleStructureDecisionView::reason)
                .containsExactly(false, "ACTOR_SCOPE_LIMIT");
        assertThat(decision(preview, "delete-action"))
                .extracting(RoleStructureDecisionView::allowed, RoleStructureDecisionView::reason)
                .containsExactly(false, "TARGET_ROLE_DENY");
        assertThat(decision(preview, "summary"))
                .extracting(RoleStructureDecisionView::allowed, RoleStructureDecisionView::reason)
                .containsExactly(true, "UNRESTRICTED");
        assertThat(decision(preview, "menu-customer"))
                .extracting(RoleStructureDecisionView::nodeType, RoleStructureDecisionView::reason)
                .containsExactly("MENU", "ACTOR_SCOPE_LIMIT");

        verify(workspaceService).get("session-1");
        verify(menuMapper).findActivePathByPage(7L, "page-1", null);
    }

    @Test
    void targetsAreSessionScopedAndExcludeInactiveRoles() throws Exception {
        when(workspaceService.get("session-1")).thenReturn(session("{}"));
        when(roleService.findByTenantId(7L)).thenReturn(List.of(
                role(2L, "role-b", "b", "Beta", 20, "ACTIVE"),
                role(1L, "role-a", "a", "Alpha", 10, "ACTIVE"),
                role(3L, "role-off", "off", "Inactive", 5, "INACTIVE")));

        assertThat(service.targets("session-1"))
                .extracting(target -> target.rolePid())
                .containsExactly("role-a", "role-b");
        verify(workspaceService).get("session-1");
    }

    @Test
    void missingOrCrossTenantRoleFailsClosed() throws Exception {
        when(workspaceService.get("session-1")).thenReturn(session("{}"));
        when(roleService.findByPid("foreign-role")).thenReturn(null);

        assertThatThrownBy(() -> service.preview("session-1", "foreign-role"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND")
                .hasMessageContaining("authoring.role-preview.role-not-found");
    }

    private SessionView session(String snapshot) throws Exception {
        return new SessionView(
                "session-1",
                "changes-1",
                "page-1",
                null,
                11L,
                "DRAFT",
                "AUTHORING",
                "ACTIVE",
                1L,
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
                objectMapper.readTree(snapshot),
                objectMapper.createObjectNode(),
                null,
                Instant.now().plusSeconds(60));
    }

    private static Role role(
            Long id,
            String pid,
            String code,
            String name,
            Integer priority,
            String status) {
        Role role = new Role();
        role.setId(id);
        role.setPid(pid);
        role.setCode(code);
        role.setName(name);
        role.setPriority(priority);
        role.setStatus(status);
        role.setDeletedFlag(false);
        return role;
    }

    private static RoleStructureDecisionView decision(
            RoleStructurePreviewView preview,
            String nodeId) {
        return preview.decisions().stream()
                .filter(item -> nodeId.equals(item.nodeId()))
                .findFirst()
                .orElseThrow();
    }
}
