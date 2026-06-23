package com.auraboot.framework.rbac.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.service.RolePermissionService;
import com.auraboot.framework.plugin.service.PluginResourceTracker;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.service.RoleService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoleControllerTest {

    @Mock
    private RoleService roleService;

    @Mock
    private RolePermissionService rolePermissionService;

    @Mock
    private PluginResourceTracker pluginResourceTracker;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private RoleController controller;

    @BeforeEach
    void setUp() {
        controller = new RoleController();
        ReflectionTestUtils.setField(controller, "roleService", roleService);
        ReflectionTestUtils.setField(controller, "rolePermissionService", rolePermissionService);
        ReflectionTestUtils.setField(controller, "pluginResourceTracker", pluginResourceTracker);
        MetaContext.setContext(100L, 700L, "user-pid", "operator");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void getAllRoles_returnsPublicPidContractWithoutInternalIds() throws Exception {
        Role role = new Role();
        role.setId(327410003776507904L);
        role.setPid("role_e2et_viewer");
        role.setTenantId(100L);
        role.setCode("e2et_viewer");
        role.setName("E2E Test Viewer");
        role.setDescription("Read-only test role");
        role.setType("CUSTOM");
        role.setScopeType("TENANT");
        role.setScopeContent("{\"tenantId\":100}");
        role.setDefaultDataScopeType("all");
        role.setPriority(50);
        role.setStatus("active");
        role.setIsDefault(false);
        role.setIsSystem(false);
        role.setDeletedFlag(false);
        role.setCreatedBy(700L);
        role.setUpdatedBy(701L);

        when(roleService.findByTenantId(100L)).thenReturn(List.of(role));

        JsonNode first = objectMapper.readTree(objectMapper.writeValueAsString(controller.getAllRoles()))
                .path("data")
                .path(0);

        assertEquals("role_e2et_viewer", first.path("pid").asText());
        assertEquals("e2et_viewer", first.path("code").asText());
        assertEquals("E2E Test Viewer", first.path("name").asText());
        assertFalse(first.has("id"));
        assertFalse(first.has("tenantId"));
        assertFalse(first.has("scopeContent"));
        assertFalse(first.has("createdBy"));
        assertFalse(first.has("updatedBy"));
        assertFalse(first.has("deletedFlag"));
    }
}
