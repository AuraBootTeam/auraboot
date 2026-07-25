package com.auraboot.framework.integration.security.rbac;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.integration.TestIdGenerator;
import com.auraboot.framework.meta.dto.DataPermissionPolicyCreateRequest;
import com.auraboot.framework.meta.entity.DataPermissionPolicy;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import com.auraboot.framework.meta.service.DataPermissionPolicyService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterizes the CURRENT read-gate enforcement of a SELF data scope, so the step that routes those
 * gates through the permit plan (authz §11.10 stage 3, follow-up ③′-2) can be proven to preserve it.
 * This changes no production code — it pins behaviour.
 *
 * <p>The gate under characterization is the engine's {@link DataPermissionEngine#canAccessRecord} —
 * the {@code @1034} read-check that runs before every single-record write. Today it decides via the
 * engine's policies; ③′-2 will make it honour the plan's scope grade when a command scope is in force.
 * A member bound to a SELF row policy may reach a record they own and not one another user owns; a
 * member with no policy reaches both. When ③′-2 lands, these same assertions must still hold with the
 * scope coming from the plan.</p>
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Read-gate SELF scope — characterization of canAccessRecord before the plan takes over")
class ScopeReadGateCharacterizationIT extends BaseIntegrationTest {

    @Autowired private DataPermissionEngine dataPermissionEngine;
    @Autowired private DataPermissionPolicyService dataPermissionPolicyService;
    @Autowired private JdbcTemplate jdbc;

    private final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
    private final String model = "spc_" + suffix;

    private Long tenantId;
    private Long selfUserId;      // member bound to a SELF policy
    private Long selfMemberId;
    private Long openUserId;      // member with no policy at all
    private Long openMemberId;
    private String policyPid;
    private String rolePid;

    @BeforeAll
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void setUp() {
        tenantId = TestIdGenerator.uniqueTenantId();
        selfUserId = TestIdGenerator.uniqueUserId();
        openUserId = selfUserId + 4_000_001L;

        jdbc.update("INSERT INTO ab_tenant (id, pid, name, status, deleted_flag) "
                        + "VALUES (?, ?, ?, 'active', FALSE) ON CONFLICT (id) DO NOTHING",
                tenantId, "tn_" + tenantId, "scope_char_" + tenantId);
        selfMemberId = insertMember(selfUserId);
        openMemberId = insertMember(openUserId);

        // A role the SELF member holds; the open member holds nothing.
        long roleId = System.nanoTime() & 0x7fffffffffffffffL;
        rolePid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_role (id, pid, tenant_id, name, code, type, scope_type, status, "
                        + "deleted_flag, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'CUSTOM', 'TENANT', 'ACTIVE', FALSE, now(), now())",
                roleId, rolePid, tenantId, "scope char role", "char_role_" + suffix);
        jdbc.update("INSERT INTO ab_user_role (id, pid, tenant_id, member_id, role_id, assign_type, "
                        + "status, deleted_flag, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'DIRECT', 'ACTIVE', FALSE, now())",
                System.nanoTime() & 0x7fffffffffffffffL, UniqueIdGenerator.generate(), tenantId,
                selfMemberId, roleId);

        // A SELF row policy on the model, bound to that role.
        DataPermissionPolicyCreateRequest req = new DataPermissionPolicyCreateRequest();
        req.setName("scope char self " + suffix);
        req.setModelCode(model);
        req.setPolicyType("row");
        req.setScopeType("self");
        MetaContext.setContext(tenantId, selfUserId, "u-" + selfUserId, "scope-char-setup");
        try {
            DataPermissionPolicy policy = dataPermissionPolicyService.create(req);
            policyPid = policy.getPid();
            dataPermissionPolicyService.bindToRole(policyPid, rolePid);
        } finally {
            MetaContext.clear();
        }
    }

    @AfterAll
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void tearDown() {
        try {
            jdbc.update("DELETE FROM ab_data_permission_role_binding WHERE policy_pid = ?", policyPid);
            jdbc.update("DELETE FROM ab_data_permission_policy WHERE pid = ?", policyPid);
            jdbc.update("DELETE FROM ab_user_role WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_role WHERE pid = ?", rolePid);
            jdbc.update("DELETE FROM ab_tenant_member WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_tenant WHERE id = ?", tenantId);
        } catch (Exception ignored) {
        }
        MetaContext.clear();
    }

    @Test
    @DisplayName("a SELF-scoped member may reach a record they own, but not one another user owns")
    void selfScopedMemberReachesOwnNotOthers() {
        MetaContext.setContext(tenantId, selfUserId, "u-" + selfUserId, "self-member");
        MetaContext.setMemberId(selfMemberId);
        try {
            assertThat(dataPermissionEngine.canAccessRecord(tenantId, model, selfUserId, ownedBy(selfUserId)))
                    .as("a SELF-scoped member reaches their own record")
                    .isTrue();
            assertThat(dataPermissionEngine.canAccessRecord(tenantId, model, selfUserId, ownedBy(openUserId)))
                    .as("a SELF-scoped member is refused another user's record — this is what the plan must preserve")
                    .isFalse();
        } finally {
            MetaContext.clear();
        }
    }

    @Test
    @DisplayName("a member with no policy reaches any record — the SELF policy is what discriminates")
    void memberWithNoPolicyReachesAnyRecord() {
        MetaContext.setContext(tenantId, openUserId, "u-" + openUserId, "open-member");
        MetaContext.setMemberId(openMemberId);
        try {
            assertThat(dataPermissionEngine.canAccessRecord(tenantId, model, openUserId, ownedBy(openUserId))).isTrue();
            assertThat(dataPermissionEngine.canAccessRecord(tenantId, model, openUserId, ownedBy(selfUserId)))
                    .as("no policy = unrestricted read, so the SELF denial above is the policy, not some other gate")
                    .isTrue();
        } finally {
            MetaContext.clear();
        }
    }

    private Map<String, Object> ownedBy(Long userId) {
        Map<String, Object> record = new HashMap<>();
        record.put("created_by", userId);
        record.put("pid", "rec_" + userId);
        return record;
    }

    private Long insertMember(Long userId) {
        long memberId = System.nanoTime() & 0x7fffffffffffffffL;
        jdbc.update("INSERT INTO ab_tenant_member (id, pid, tenant_id, user_id, status, deleted_flag) "
                        + "VALUES (?, ?, ?, ?, 'active', FALSE)",
                memberId, UniqueIdGenerator.generate(), tenantId, userId);
        return memberId;
    }
}
