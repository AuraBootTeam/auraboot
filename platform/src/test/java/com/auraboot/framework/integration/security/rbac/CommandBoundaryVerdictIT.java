package com.auraboot.framework.integration.security.rbac;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.integration.TestIdGenerator;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.service.impl.pipeline.CommandAuthorizationVerdict;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.meta.service.impl.pipeline.phases.CommandAuthorizationPhase;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.tenant.service.TenantBootstrapService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The command boundary's verdict, resolved through REAL roles rather than a mocked permission
 * service — a bootstrapped {@code tenant_admin} and a role-less {@code tenant_member}, using the
 * same {@link UserPermissionService} the interceptor uses.
 *
 * <p>Mocked-permission unit tests cannot see the property that matters most here: an admin holds the
 * {@code "*"} wildcard, so every code resolves for them. If the boundary expressed "authorized" by
 * simply not throwing, an admin would appear authorized for a command that declares nothing —
 * exactly the population whose silence must never be read as approval. Running both roles through
 * the same phase is what proves the verdict tracks the DECLARATION, not the caller's power.</p>
 *
 * <p>Codes come from the RBAC SOT matrix so this cannot drift from the access baseline: an allow
 * code a member holds, a deny code only an admin holds, and a command declaring nothing.</p>
 *
 * @see com.auraboot.framework.meta.service.impl.pipeline.CommandAuthorizationVerdict
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("Command boundary verdict — real tenant_admin vs role-less tenant_member")
class CommandBoundaryVerdictIT extends BaseIntegrationTest {

    private static final String DEPLOYMENT = "platform-baseline";

    @Autowired private TenantBootstrapService tenantBootstrapService;
    @Autowired private UserPermissionService userPermissionService;
    @Autowired private JdbcTemplate jdbc;

    private CommandAuthorizationPhase phase;
    private Long tenantId;
    private Long adminUserId;
    private Long adminMemberId;
    private Long memberUserId;
    private Long memberMemberId;

    /** A code the SOT matrix grants a plain member. */
    private String memberAllowCode;
    /** A code the SOT matrix denies a plain member — the admin still holds it via the wildcard. */
    private String memberDenyCode;

    @BeforeEach
    void setup() {
        RbacAccessMatrix matrix = RbacAccessMatrix.load();
        RbacAccessMatrix.RoleEntry member = matrix.role(DEPLOYMENT, "tenant_member");
        memberAllowCode = member.allow().getFirst();
        memberDenyCode = member.deny().getFirst();

        tenantId = TestIdGenerator.uniqueTenantId();
        adminUserId = testUser.getId();
        memberUserId = TestIdGenerator.uniqueUserId();

        jdbc.update("INSERT INTO ab_tenant (id, pid, name, status, deleted_flag) "
                        + "VALUES (?, ?, ?, 'active', FALSE) ON CONFLICT (id) DO NOTHING",
                tenantId, "tn_" + tenantId, "cmd_verdict_" + tenantId);
        adminMemberId = insertMember(adminUserId);
        memberMemberId = insertMember(memberUserId);

        TenantBootstrapService.BootstrapResult result =
                tenantBootstrapService.bootstrapTenant(tenantId, adminUserId);
        assertThat(result.isSuccess())
                .as("tenant bootstrap must succeed: %s", result.getMessage())
                .isTrue();

        userPermissionService.evictUserPermissions(adminUserId);
        userPermissionService.evictUserPermissions(memberUserId);

        phase = new CommandAuthorizationPhase(userPermissionService);
    }

    private Long insertMember(Long userId) {
        long memberId = System.nanoTime() & 0x7fffffffffffffffL;
        jdbc.update("INSERT INTO ab_tenant_member (id, pid, tenant_id, user_id, status, deleted_flag) "
                        + "VALUES (?, ?, ?, ?, 'active', FALSE)",
                memberId, UniqueIdGenerator.generate(), tenantId, userId);
        return memberId;
    }

    /**
     * Permission resolution is tenant-scoped and reads the member behind the user, exactly as the
     * interceptor does — without the context set, every code resolves to false and a test would be
     * asserting on an empty permission set rather than on the role.
     */
    private void asRole(Long userId, Long memberId, Runnable body) {
        MetaContext.setContext(tenantId, userId, "u-" + userId, "cmd-verdict-" + userId);
        MetaContext.setMemberId(memberId);
        userPermissionService.evictUserPermissions(userId);
        try {
            body.run();
        } finally {
            MetaContext.clear();
        }
    }

    @AfterEach
    void cleanup() {
        if (tenantId != null) {
            jdbc.update("DELETE FROM ab_user_role WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_role_permission WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_permission WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_role WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_agent_definition WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_menu WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_tenant_member WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_tenant WHERE id = ?", tenantId);
        }
        if (adminUserId != null) userPermissionService.evictUserPermissions(adminUserId);
        if (memberUserId != null) userPermissionService.evictUserPermissions(memberUserId);
        MetaContext.clear();
    }

    @Test
    @DisplayName("admin: a declared permission the wildcard covers is recorded as the authorizing code")
    void adminIsAuthorizedByTheDeclaredCode() {
        CommandPipelineContext ctx = context(List.of(memberDenyCode), adminUserId);

        asRole(adminUserId, adminMemberId, () -> phase.execute(ctx));

        assertThat(ctx.getAuthorizationVerdict().isAuthorized()).isTrue();
        assertThat(ctx.getAuthorizationVerdict().permissionCode()).isEqualTo(memberDenyCode);
    }

    @Test
    @DisplayName("member: a declared permission they hold via the L1 baseline authorizes them")
    void memberIsAuthorizedByABaselineCode() {
        CommandPipelineContext ctx = context(List.of(memberAllowCode), memberUserId);

        asRole(memberUserId, memberMemberId, () -> phase.execute(ctx));

        assertThat(ctx.getAuthorizationVerdict().isAuthorized()).isTrue();
        assertThat(ctx.getAuthorizationVerdict().permissionCode()).isEqualTo(memberAllowCode);
    }

    @Test
    @DisplayName("member: a declared permission they lack is still denied, and leaves no verdict")
    void memberIsDeniedTheCodeTheMatrixDeniesThem() {
        CommandPipelineContext ctx = context(List.of(memberDenyCode), memberUserId);

        asRole(memberUserId, memberMemberId, () ->
                assertThatThrownBy(() -> phase.execute(ctx))
                        .isInstanceOf(BusinessException.class)
                        .hasMessageContaining("Command permission denied"));

        assertThat(ctx.getAuthorizationVerdict())
                .as("a denied command must not leave an authorization behind")
                .isNull();
    }

    /**
     * The load-bearing one. An admin resolves every code, so any check that asks "did this caller
     * pass?" says yes here. The verdict must still be NOT_APPLICABLE, because the COMMAND granted
     * nothing — otherwise the ~200 commands that declare no permissions would silently become
     * authorized for exactly the callers with the most reach.
     */
    @Test
    @DisplayName("neither role turns an undeclared command into an authorization — not even admin")
    void anUndeclaredCommandAuthorizesNobody() {
        Map<Long, Long> membersByUser = Map.of(adminUserId, adminMemberId, memberUserId, memberMemberId);
        for (Long userId : List.of(adminUserId, memberUserId)) {
            CommandPipelineContext ctx = context(null, userId);

            asRole(userId, membersByUser.get(userId), () -> phase.execute(ctx));

            assertThat(ctx.getAuthorizationVerdict().isAuthorized())
                    .as("user %s must not be authorized by a command that declares nothing", userId)
                    .isFalse();
            assertThat(ctx.getAuthorizationVerdict().reason())
                    .isEqualTo(CommandAuthorizationVerdict.REASON_NO_DECLARED_PERMISSIONS);
        }
    }

    private CommandPipelineContext context(List<String> permissions, Long userId) {
        Map<String, Object> execConfig = new HashMap<>();
        if (permissions != null) {
            execConfig.put("permissions", permissions);
        }
        return CommandPipelineContext.builder()
                .commandCode("qo_quote_common:batch_source_prices")
                .request(new CommandExecuteRequest())
                .tenantId(tenantId)
                .userId(userId)
                .startTime(System.currentTimeMillis())
                .execConfig(execConfig)
                .build();
    }
}
