package com.auraboot.framework.permission.capability;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.meta.service.impl.pipeline.phases.CommandAuthorizationPhase;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.plugin.dto.imports.CapabilityDefinitionDTO;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end capability-driven enforcement lifecycle for QuoteOps + BOM standardization.
 *
 * <p>Validates the full permission v2 chain the authoring/golden tests don't cover:
 * <pre>
 *   capability (v2) -> resolved permission codes -> role -> member -> command enforcement
 * </pre>
 * for a freshly-added member, through a grant/revoke lifecycle:
 * <ol>
 *   <li>add a member + assign a fresh role (no product permissions yet) -> command DENIED (403)</li>
 *   <li>grant the capability to the role via the real v2 API
 *       ({@link CapabilityViewService#applyCapabilitySelection}) -> command ALLOWED</li>
 *   <li>revoke the capability via the same v2 API -> command DENIED (403) again</li>
 * </ol>
 *
 * <p>Every layer is real: {@link CapabilityRegistryService} (declaration), {@link CapabilityViewService}
 * (capability -> code grant/revoke), {@link UserPermissionService} (effective permissions) and
 * {@link CommandAuthorizationPhase} (the actual command-pipeline gate that throws FORBIDDEN). The
 * required-permission fed to the gate is exactly what the real {@code bom:import_material_library} /
 * {@code qo_price_evidence_create} commands declare.
 */
@DisplayName("Capability lifecycle enforcement — QuoteOps + BOM (member grant/revoke)")
class CapabilityLifecycleEnforcementIT extends BaseIntegrationTest {

    @Autowired
    private CapabilityRegistryService capabilityRegistryService;
    @Autowired
    private CapabilityViewService capabilityViewService;
    @Autowired
    private UserPermissionService userPermissionService;
    @Autowired
    private PermissionMapper permissionMapper;
    @Autowired
    private UserService userService;
    @Autowired
    private TenantMemberService tenantMemberService;
    @Autowired
    private UserRoleService userRoleService;
    @Autowired
    private RoleService roleService;
    @Autowired
    private CommandAuthorizationPhase commandAuthorizationPhase;

    @BeforeEach
    void ctx() {
        applyTestMetaContext();
    }

    @Test
    @DisplayName("BOM: member with bom.cap.library_manage can run bom:import_material_library; revoke -> 403")
    void bomLibraryManageLifecycle() {
        runLifecycle(
                "bom.cap.library_manage", List.of("bom.library.manage"),
                "bom.library.manage", "bom:import_material_library",
                "perm-life-bom@auraboot.com", "perm_life_bom");
    }

    @Test
    @DisplayName("Quote: member with qo.cap.sourcing can run qo_price_evidence_create; revoke -> 403")
    void quoteSourcingLifecycle() {
        runLifecycle(
                "qo.cap.sourcing", List.of("qo.price.manage", "qo.process_fee.manage"),
                "qo.price.manage", "qo_price_evidence_create",
                "perm-life-quote@auraboot.com", "perm_life_quote");
    }

    private void runLifecycle(String capCode, List<String> includes, String enforcedPerm,
                              String commandCode, String memberEmail, String roleCode) {
        // --- arrange: add a member + assign a fresh role that has NO product permissions yet.
        // (Do member creation FIRST: userService.signUp mutates MetaContext's tenant, which would
        // otherwise make the capability registry save/read against an inconsistent tenant.)
        User member = signUpOrFind(memberEmail);
        TenantMember tm = tenantMemberService.addMember(member.getId(), getTestTenant().getId(), "active");
        Role role = createRole(roleCode);
        userRoleService.assignRolesToMember(tm.getId(), List.of(role.getId()), getTestTenant().getId(), null);

        // --- arrange: pin the tenant context, then register the permission(s) + capability
        // declaration (mirrors plugin import). Re-pin before every capability-registry / view call.
        applyTestMetaContext();
        includes.forEach(this::registerPermission);
        capabilityRegistryService.saveDefinition(CapabilityDefinitionDTO.builder()
                .code(capCode).group("test").nameZhCN(capCode).nameEn(capCode)
                .includes(includes).tier("admin").order(1).build());

        // clean slate: ensure the role carries none of the capability codes from a previous run
        applyTestMetaContext();
        capabilityViewService.applyCapabilitySelection(role.getId(), Set.of());

        // --- 1. baseline: no capability -> command denied. hasPermission/CommandAuthorizationPhase
        // resolve roles via MetaContext.memberId (not the userId arg), so act AS the new member.
        actAsMember(tm.getId(), member.getId());
        assertFalse(userPermissionService.hasPermission(member.getId(), enforcedPerm),
                "fresh member must not hold " + enforcedPerm);
        assertDenied(member.getId(), commandCode, enforcedPerm);

        // --- 2. grant the capability via the real v2 API -> command allowed
        applyTestMetaContext();
        capabilityViewService.applyCapabilitySelection(role.getId(), Set.of(capCode));
        actAsMember(tm.getId(), member.getId());
        assertTrue(userPermissionService.hasPermission(member.getId(), enforcedPerm),
                "granting " + capCode + " must resolve to " + enforcedPerm);
        assertAllowed(member.getId(), commandCode, enforcedPerm);

        // --- 3. revoke the capability via the same v2 API -> command denied again
        applyTestMetaContext();
        capabilityViewService.applyCapabilitySelection(role.getId(), Set.of());
        actAsMember(tm.getId(), member.getId());
        assertFalse(userPermissionService.hasPermission(member.getId(), enforcedPerm),
                "revoking " + capCode + " must drop " + enforcedPerm);
        assertDenied(member.getId(), commandCode, enforcedPerm);
    }

    /** Switch MetaContext to act as the given member (the basis hasPermission resolves), and clear its cache. */
    private void actAsMember(Long memberId, Long userId) {
        applyTestMetaContext();
        com.auraboot.framework.application.tenant.MetaContext.setMemberId(memberId);
        userPermissionService.evictUserPermissions(userId);
    }

    private void assertAllowed(Long userId, String commandCode, String perm) {
        assertDoesNotThrow(() -> commandAuthorizationPhase.execute(ctx(userId, commandCode, perm)),
                "command " + commandCode + " must be allowed once the capability is granted");
    }

    private void assertDenied(Long userId, String commandCode, String perm) {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> commandAuthorizationPhase.execute(ctx(userId, commandCode, perm)),
                "command " + commandCode + " must be denied without the capability");
        assertTrue(String.valueOf(ex.getMessage()).contains("Command permission denied"),
                "expected a command-permission-denied error, got: " + ex.getMessage());
    }

    private CommandPipelineContext ctx(Long userId, String commandCode, String perm) {
        Map<String, Object> execConfig = new HashMap<>();
        execConfig.put("permissions", List.of(perm));
        return CommandPipelineContext.builder()
                .userId(userId).commandCode(commandCode).execConfig(execConfig).build();
    }

    private User signUpOrFind(String email) {
        User existing = userService.findByEmail(email);
        if (existing != null) {
            return existing;
        }
        User created = userService.signUp(email, "test-password-123");
        return created != null ? created : userService.findByEmail(email);
    }

    private Role createRole(String code) {
        List<Role> existing = roleService.lambdaQuery()
                .eq(Role::getTenantId, getTestTenant().getId())
                .eq(Role::getCode, code)
                .eq(Role::getDeletedFlag, false)
                .list();
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        Role role = new Role();
        role.setPid(UniqueIdGenerator.generate());
        role.setName(code);
        role.setCode(code);
        role.setDescription("capability lifecycle enforcement test role");
        role.setType("custom");
        role.setScopeType("tenant");
        role.setStatus("active");
        role.setTenantId(getTestTenant().getId());
        role.setIsDefault(false);
        role.setIsSystem(false);
        role.setDeletedFlag(false);
        role.setPriority(100);
        role.setCreatedAt(Instant.now());
        role.setUpdatedAt(Instant.now());
        return roleService.createRole(role);
    }

    private void registerPermission(String code) {
        if (permissionMapper.findByCode(code) != null) {
            return;
        }
        String[] parts = code.split("\\.");
        Permission permission = new Permission();
        permission.setPid(UniqueIdGenerator.generate());
        permission.setCode(code);
        permission.setName(code);
        permission.setResourceType(parts.length > 0 ? parts[0] : "system");
        permission.setResourceCode(parts.length > 1 ? parts[1] : code);
        permission.setAction(parts.length > 2 ? parts[2] : "manage");
        permission.setSource("manual");
        permission.setStatus("active");
        permission.setDeletedFlag(false);
        permission.setTenantId(getTestTenant().getId());
        permission.setCreatedAt(Instant.now());
        permission.setUpdatedAt(Instant.now());
        permissionMapper.insert(permission);
    }
}
