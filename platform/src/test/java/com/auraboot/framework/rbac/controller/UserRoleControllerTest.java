package com.auraboot.framework.rbac.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.rbac.dto.AssignRolesByCodeRequest;
import com.auraboot.framework.rbac.dto.AssignRolesByPidRequest;
import com.auraboot.framework.rbac.dto.UserRoleResponse;
import com.auraboot.framework.rbac.entity.UserRole;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserRoleControllerTest {

    @Mock
    private UserRoleService userRoleService;

    private UserRoleController controller;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        controller = new UserRoleController();
        ReflectionTestUtils.setField(controller, "userRoleService", userRoleService);
        MetaContext.setContext(100L, 700L, "user-pid", "operator");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void assignByCode_acceptsMemberPidAndRoleCodes() {
        AssignRolesByCodeRequest request = new AssignRolesByCodeRequest();
        request.setMemberPid("member_e2e_viewer");
        request.setRoleCodes(List.of("e2et_viewer"));
        when(userRoleService.assignRolesToMemberByRoleCodes("member_e2e_viewer", List.of("e2et_viewer"), 100L, 700L))
                .thenReturn(true);

        ApiResponse<Boolean> response = controller.assignRolesToMemberByCode(request, 700L);

        assertTrue(response.isSuccess());
        assertTrue(response.getData());
        verify(userRoleService).assignRolesToMemberByRoleCodes("member_e2e_viewer", List.of("e2et_viewer"), 100L, 700L);
    }

    @Test
    void assignByPid_acceptsMemberPidAndRolePids() {
        AssignRolesByPidRequest request = new AssignRolesByPidRequest();
        request.setMemberPid("member_e2e_operator");
        request.setRolePids(List.of("role_e2et_operator"));
        when(userRoleService.assignRolesToMemberByRolePids("member_e2e_operator", List.of("role_e2et_operator"), 100L, 700L))
                .thenReturn(true);

        ApiResponse<Boolean> response = controller.assignRolesToMemberByPid(request, 700L);

        assertTrue(response.isSuccess());
        assertTrue(response.getData());
        verify(userRoleService).assignRolesToMemberByRolePids("member_e2e_operator", List.of("role_e2et_operator"), 100L, 700L);
    }

    @Test
    void removeByPid_acceptsMemberPidAndRolePids() {
        AssignRolesByPidRequest request = new AssignRolesByPidRequest();
        request.setMemberPid("member_e2e_operator");
        request.setRolePids(List.of("role_e2et_operator"));
        when(userRoleService.removeRolesFromMemberByRolePids("member_e2e_operator", List.of("role_e2et_operator"), 100L))
                .thenReturn(true);

        ApiResponse<Boolean> response = controller.removeRolesFromMemberByPid(request);

        assertTrue(response.isSuccess());
        assertTrue(response.getData());
        verify(userRoleService).removeRolesFromMemberByRolePids("member_e2e_operator", List.of("role_e2et_operator"), 100L);
    }

    @Test
    void syncByPid_acceptsMemberPidAndRolePids() {
        AssignRolesByPidRequest request = new AssignRolesByPidRequest();
        request.setMemberPid("member_e2e_operator");
        request.setRolePids(List.of("role_e2et_operator", "role_e2et_viewer"));
        when(userRoleService.syncMemberRolesByRolePids(
                "member_e2e_operator", List.of("role_e2et_operator", "role_e2et_viewer"), 100L, 700L))
                .thenReturn(true);

        ApiResponse<Boolean> response = controller.syncMemberRolesByPid(request, 700L);

        assertTrue(response.isSuccess());
        assertTrue(response.getData());
        verify(userRoleService).syncMemberRolesByRolePids(
                "member_e2e_operator", List.of("role_e2et_operator", "role_e2et_viewer"), 100L, 700L);
    }

    @Test
    void batchRemoveByPid_acceptsUserRolePids() {
        when(userRoleService.batchRemoveRolesByPids(List.of("ur_pid_1", "ur_pid_2"), 100L))
                .thenReturn(2);

        ApiResponse<Boolean> response = controller.batchRemoveUserRolesByPid(List.of("ur_pid_1", "ur_pid_2"));

        assertTrue(response.isSuccess());
        assertTrue(response.getData());
        verify(userRoleService).batchRemoveRolesByPids(List.of("ur_pid_1", "ur_pid_2"), 100L);
    }

    @Test
    void batchAssignByPid_acceptsMemberPidAndRolePids() {
        AssignRolesByPidRequest first = new AssignRolesByPidRequest();
        first.setMemberPid("member_e2e_operator");
        first.setRolePids(List.of("role_e2et_operator"));
        AssignRolesByPidRequest second = new AssignRolesByPidRequest();
        second.setMemberPid("member_e2e_viewer");
        second.setRolePids(List.of("role_e2et_viewer"));
        when(userRoleService.assignRolesToMemberByRolePids(
                "member_e2e_operator", List.of("role_e2et_operator"), 100L, 700L))
                .thenReturn(true);
        when(userRoleService.assignRolesToMemberByRolePids(
                "member_e2e_viewer", List.of("role_e2et_viewer"), 100L, 700L))
                .thenReturn(true);

        ApiResponse<Boolean> response = controller.batchAssignRolesByPid(List.of(first, second), 700L);

        assertTrue(response.isSuccess());
        assertTrue(response.getData());
        verify(userRoleService).assignRolesToMemberByRolePids(
                "member_e2e_operator", List.of("role_e2et_operator"), 100L, 700L);
        verify(userRoleService).assignRolesToMemberByRolePids(
                "member_e2e_viewer", List.of("role_e2et_viewer"), 100L, 700L);
    }

    @Test
    void getUserRoles_returnsPidOnlyPublicContract() throws Exception {
        UserRoleResponse assignment = new UserRoleResponse();
        assignment.setPid("ur_e2e_1");
        assignment.setMemberPid("member_e2e_viewer");
        assignment.setRolePid("role_e2et_viewer");
        assignment.setStatus("active");
        Page<UserRoleResponse> page = new Page<>(1, 10, 1);
        page.setRecords(List.of(assignment));

        when(userRoleService.findUserRoleResponses(
                1, 10, "member_e2e_viewer", "role_e2et_viewer", null, null, 100L, null))
                .thenReturn(page);

        JsonNode first = objectMapper.readTree(objectMapper.writeValueAsString(
                        controller.getUserRoles(1, 10, "member_e2e_viewer", "role_e2et_viewer", null, null, null)))
                .path("data")
                .path("records")
                .path(0);

        assertEquals("ur_e2e_1", first.path("pid").asText());
        assertEquals("member_e2e_viewer", first.path("memberPid").asText());
        assertEquals("role_e2et_viewer", first.path("rolePid").asText());
        assertFalse(first.has("id"));
        assertFalse(first.has("tenantId"));
        assertFalse(first.has("memberId"));
        assertFalse(first.has("roleId"));
    }

    @Test
    void getMemberRolePids_returnsRolePids() {
        when(userRoleService.getRolePidsByMemberPidAndTenantId("member_e2e_viewer", 100L))
                .thenReturn(List.of("role_e2et_viewer"));

        ApiResponse<List<String>> response = controller.getMemberRolePids("member_e2e_viewer");

        assertTrue(response.isSuccess());
        assertEquals(List.of("role_e2et_viewer"), response.getData());
        verify(userRoleService).getRolePidsByMemberPidAndTenantId("member_e2e_viewer", 100L);
    }

    @Test
    void legacyIdMutationEndpoints_emitDeprecationHeaders() {
        when(userRoleService.assignRolesToMember(1L, List.of(10L), 100L, 700L)).thenReturn(true);
        when(userRoleService.removeRolesFromMember(1L, List.of(10L), 100L)).thenReturn(true);
        when(userRoleService.syncMemberRoles(1L, List.of(10L), 100L, 700L)).thenReturn(true);
        when(userRoleService.batchAssignRoles(anyList())).thenReturn(1);
        when(userRoleService.batchRemoveRoles(List.of(99L))).thenReturn(1);

        MockHttpServletResponse assign = bindResponse();
        controller.assignRolesToMember(1L, List.of(10L), 700L);
        assertLegacyDeprecation(assign, "/api/user-roles/assign", "/api/user-roles/assign-by-pid");

        MockHttpServletResponse remove = bindResponse();
        controller.removeRolesFromMember(1L, List.of(10L));
        assertLegacyDeprecation(remove, "/api/user-roles/remove", "/api/user-roles/remove-by-pid");

        MockHttpServletResponse sync = bindResponse();
        controller.syncMemberRoles(1L, List.of(10L), 700L);
        assertLegacyDeprecation(sync, "/api/user-roles/sync", "/api/user-roles/sync-by-pid");

        MockHttpServletResponse batchAssign = bindResponse();
        controller.batchAssignRoles(List.of(new UserRole()), 700L);
        assertLegacyDeprecation(batchAssign, "/api/user-roles/batch-assign", "/api/user-roles/batch-assign-by-pid");

        MockHttpServletResponse batchRemove = bindResponse();
        controller.batchRemoveUserRoles(List.of(99L));
        assertLegacyDeprecation(batchRemove, "/api/user-roles/batch-remove", "/api/user-roles/batch-remove-by-pid");
    }

    private static MockHttpServletResponse bindResponse() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));
        return response;
    }

    private static void assertLegacyDeprecation(
            MockHttpServletResponse response,
            String legacyPath,
            String replacementPath) {
        assertEquals("true", response.getHeader("Deprecation"));
        assertEquals(legacyPath, response.getHeader("X-Aura-Deprecated-Endpoint"));
        assertEquals(replacementPath, response.getHeader("X-Aura-Replacement-Endpoint"));
        assertEquals("299 AuraBoot \"Deprecated endpoint; use " + replacementPath + "\"",
                response.getHeader("Warning"));
    }
}
