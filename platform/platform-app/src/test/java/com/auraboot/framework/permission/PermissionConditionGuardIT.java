package com.auraboot.framework.permission;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.permission.dto.PermissionCreateRequest;
import com.auraboot.framework.permission.dto.PermissionDTO;
import com.auraboot.framework.permission.engine.PermissionEvaluator;
import com.auraboot.framework.permission.engine.model.PermissionResult;
import com.auraboot.framework.permission.service.PermissionService;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Permission Governance S1 (Plan B) — runtime enforcement of the materialized
 * {@code condition_ast} guard.
 *
 * <p>Inserts a real GRANT binding (role bound to the shared test member) carrying a decision
 * {@code condition_ast} and asserts that {@link PermissionEvaluator#canOperate} enforces it
 * end-to-end through the real evaluation pipeline against {@code aura_boot}:
 * <ul>
 *   <li>{@code amount <= 50000} guard: amount 10000 → ALLOW, amount 80000 → DENY.</li>
 *   <li>missing {@code amount} field → UNKNOWN → DENY (default-deny).</li>
 *   <li>unconditional grant (no condition_ast) → ALLOW (regression).</li>
 * </ul>
 *
 * <p>The condition_ast path uses {@code record.data.amount} to match the platform decision-runtime
 * wire shape (record data is nested under {@code data}); the materializing compiler emits the same.
 */
@DisplayName("Permission Condition-AST Guard (Plan B)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.BEFORE_CLASS)
class PermissionConditionGuardIT extends BaseIntegrationTest {

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private RolePermissionMapper rolePermissionMapper;

    @Autowired
    private UserPermissionService userPermissionService;

    @Autowired
    private PermissionEvaluator permissionEvaluator;

    private static final String RUN_ID = String.valueOf(System.nanoTime() % 1_000_000);

    /** condition_ast: record.data.amount <= 50000 */
    private static final String AMOUNT_LIMIT_AST = """
            {
              "type": "compare",
              "left":  { "type": "path",    "scope": "record", "path": "data.amount", "dataType": "decimal" },
              "operator": "LTE",
              "right": { "type": "literal", "value": 50000, "dataType": "decimal" }
            }
            """;

    // ── helpers ────────────────────────────────────────────────────────────

    private PermissionDTO createPermission(String suffix, String resourceCode, String action) {
        // RBAC resolves a permission by code == "<resource>:<action>" (UserPermissionServiceImpl),
        // and PolicyEvaluator.getConditionGuards looks up the same "<resource>:<action>" code, so the
        // permission code MUST equal resourceCode + ":" + action for the guard step to be reached.
        PermissionCreateRequest req = new PermissionCreateRequest();
        req.setCode(resourceCode + ":" + action);
        req.setName("Guard Test " + suffix);
        req.setDescription("Plan B condition guard integration test permission");
        req.setResourceType("model");
        req.setResourceCode(resourceCode);
        req.setAction(action);
        req.setSource("integration_test");
        PermissionDTO created = permissionService.create(req);
        userPermissionService.evictPermissionDefinitions(getTestTenant().getId());
        return created;
    }

    /** Insert a GRANT binding for the shared test role with an optional condition_ast (raw JSON). */
    private void bindGrant(Long permissionId, String conditionAstJson) {
        RolePermission binding = new RolePermission();
        binding.setPid(UniqueIdGenerator.generate());
        binding.setRoleId(getTestRole().getId());
        binding.setPermissionId(permissionId);
        binding.setGrantType("grant");
        binding.setPriority(100);
        binding.setStatus("active");
        binding.setDeletedFlag(false);
        binding.setTenantId(getTestTenant().getId());
        binding.setCreatedAt(Instant.now());
        binding.setUpdatedAt(Instant.now());
        rolePermissionMapper.insert(binding);

        if (conditionAstJson != null) {
            // condition_ast is materialized by the enterprise compiler via direct JSONB write;
            // emulate that here so the IT exercises the real read+evaluate path.
            rolePermissionMapper.update(null,
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.<RolePermission>lambdaUpdate()
                            .setSql("condition_ast = '" + conditionAstJson.replace("'", "''") + "'::jsonb")
                            .eq(RolePermission::getId, binding.getId()));
        }
        // RBAC cache must not mask the freshly-inserted grant.
        userPermissionService.evictRoleUsers(getTestTenant().getId(), getTestRole().getId());
    }

    private Long memberId() {
        return getTestTenantMember().getId();
    }

    // ── tests ──────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("amount within limit → ALLOW")
    void amountWithinLimit_allows() {
        PermissionDTO perm = createPermission("limit_ok", "guard_model_ok_" + RUN_ID, "approve");
        bindGrant(perm.getId(), AMOUNT_LIMIT_AST);

        PermissionResult result = permissionEvaluator.canOperate(
                memberId(), perm.getResourceCode(), perm.getAction(), Map.of("amount", 10000));

        assertThat(result.granted())
                .as("amount 10000 <= 50000 should satisfy the guard: %s", result.reason())
                .isTrue();
    }

    @Test
    @Order(2)
    @DisplayName("amount over limit → DENY")
    void amountOverLimit_denies() {
        PermissionDTO perm = createPermission("limit_over", "guard_model_over_" + RUN_ID, "approve");
        bindGrant(perm.getId(), AMOUNT_LIMIT_AST);

        PermissionResult result = permissionEvaluator.canOperate(
                memberId(), perm.getResourceCode(), perm.getAction(), Map.of("amount", 80000));

        assertThat(result.granted())
                .as("amount 80000 > 50000 should fail the guard: %s", result.reason())
                .isFalse();
        assertThat(result.reason()).containsIgnoringCase("guard");
    }

    @Test
    @Order(3)
    @DisplayName("missing guarded field → UNKNOWN → DENY")
    void missingField_deniesByDefault() {
        PermissionDTO perm = createPermission("limit_missing", "guard_model_missing_" + RUN_ID, "approve");
        bindGrant(perm.getId(), AMOUNT_LIMIT_AST);

        // record present but no `amount` key → path missing → UNKNOWN → deny
        PermissionResult result = permissionEvaluator.canOperate(
                memberId(), perm.getResourceCode(), perm.getAction(), Map.of("other", 1));

        assertThat(result.granted())
                .as("missing amount must default-deny (UNKNOWN != allow): %s", result.reason())
                .isFalse();
    }

    @Test
    @Order(4)
    @DisplayName("unconditional grant → ALLOW (regression)")
    void unconditionalGrant_allows() {
        PermissionDTO perm = createPermission("uncond", "guard_model_uncond_" + RUN_ID, "approve");
        bindGrant(perm.getId(), null); // no condition_ast

        PermissionResult result = permissionEvaluator.canOperate(
                memberId(), perm.getResourceCode(), perm.getAction(), Map.of("amount", 999999));

        assertThat(result.granted())
                .as("a grant with no condition_ast must allow regardless of record: %s", result.reason())
                .isTrue();
    }
}
