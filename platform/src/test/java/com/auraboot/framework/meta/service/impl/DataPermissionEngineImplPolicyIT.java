package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-stack coverage IT for {@link DataPermissionEngineImpl} WITH an effective row policy —
 * seeds the full policy chain (role + policy + role-binding + user-role) for a real tenant/member
 * so findEffectivePolicies resolves a "self"-scope row policy, exercising buildRowFilter's
 * policy-fragment build (created_by = userId). Raw teardown by tenant.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("DataPermissionEngineImpl Policy IT — effective row policy filter")
class DataPermissionEngineImplPolicyIT {

    private static final String MODEL = "perm_pol_model";

    @Autowired
    private DataPermissionEngine engine;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserService userService;
    @Autowired
    private TenantService tenantService;
    @Autowired
    private TenantMemberService tenantMemberService;

    private User testUser;
    private Tenant testTenant;
    private Long memberId;
    private boolean seeded = false;

    @BeforeEach
    void setUp() {
        if (testUser == null) {
            testUser = userService.findByEmail("permpol-test@auraboot.com");
            if (testUser == null) {
                testUser = userService.signUp("permpol-test@auraboot.com", "test-password-123");
            }
        }
        if (testTenant == null) {
            testTenant = tenantService.findByName("permpol-test-tenant");
            if (testTenant == null) {
                Tenant t = new Tenant();
                t.setPid(UniqueIdGenerator.generate());
                t.setName("permpol-test-tenant");
                t.setDisplayName("DataPermission Policy Test Tenant");
                t.setStatus("active");
                t.setContactEmail("admin@permpol-test.com");
                t.setDescription("Test tenant for DataPermission policy coverage IT");
                t.setDeletedFlag(false);
                t.setCreatedAt(Instant.now());
                t.setUpdatedAt(Instant.now());
                testTenant = tenantService.createTenant(t);
            }
        }
        TenantMember member = tenantMemberService.findByTenantIdAndUserId(testTenant.getId(), testUser.getId());
        if (member == null) {
            tenantMemberService.addMember(testUser.getId(), testTenant.getId(), "active");
            member = tenantMemberService.findByTenantIdAndUserId(testTenant.getId(), testUser.getId());
        }
        memberId = member.getId();
        MetaContext.setContext(testTenant.getId(), testUser.getId(), testUser.getPid(), testUser.getUserName());
        MetaContext.setMemberId(memberId);

        if (!seeded) {
            purge();
            long roleId = 992_100_900_001L;
            long policyId = 992_100_900_002L;
            long bindingId = 992_100_900_003L;
            long urId = 992_100_900_004L;
            String rolePid = UniqueIdGenerator.generate();
            String policyPid = UniqueIdGenerator.generate();
            jdbcTemplate.update(
                    "INSERT INTO ab_role (id, pid, tenant_id, name, deleted_flag) VALUES (?, ?, ?, 'permpol-role', false)",
                    roleId, rolePid, testTenant.getId());
            jdbcTemplate.update(
                    "INSERT INTO ab_data_permission_policy (id, pid, tenant_id, name, model_code, policy_type, scope_type, priority, enabled, created_at, updated_at) "
                            + "VALUES (?, ?, ?, 'permpol-policy', ?, 'row', 'self', 1, true, now(), now())",
                    policyId, policyPid, testTenant.getId(), MODEL);
            jdbcTemplate.update(
                    "INSERT INTO ab_data_permission_role_binding (id, tenant_id, policy_pid, role_pid, created_at) VALUES (?, ?, ?, ?, ?)",
                    bindingId, testTenant.getId(), policyPid, rolePid, Timestamp.from(Instant.now()));
            jdbcTemplate.update(
                    "INSERT INTO ab_user_role (id, pid, member_id, tenant_id, role_id, deleted_flag) VALUES (?, ?, ?, ?, ?, false)",
                    urId, UniqueIdGenerator.generate(), memberId, testTenant.getId(), roleId);
            seeded = true;
        }
    }

    @AfterAll
    void cleanup() {
        try {
            purge();
        } finally {
            MetaContext.clear();
        }
    }

    @Test
    @DisplayName("buildRowFilter applies a self-scope row policy as created_by = userId")
    void selfScopeRowPolicy() {
        String filter = engine.buildRowFilter(testTenant.getId(), MODEL, testUser.getId());
        assertNotNull(filter);
        assertTrue(filter.contains("created_by"),
                "expected the self-scope policy to add a created_by filter, got: [" + filter + "]");

        assertNotNull(engine.getFieldMaskRules(testTenant.getId(), MODEL, testUser.getId()));
    }

    private void purge() {
        Long tid = testTenant.getId();
        jdbcTemplate.update("DELETE FROM ab_user_role WHERE tenant_id = ? AND member_id = ?", tid, memberId);
        jdbcTemplate.update("DELETE FROM ab_data_permission_role_binding WHERE tenant_id = ?", tid);
        jdbcTemplate.update("DELETE FROM ab_data_permission_policy WHERE tenant_id = ?", tid);
        jdbcTemplate.update("DELETE FROM ab_role WHERE tenant_id = ? AND name = 'permpol-role'", tid);
    }
}
