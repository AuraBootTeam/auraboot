package com.auraboot.framework.integration.security.rbac;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.integration.TestIdGenerator;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPermitPlan;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.meta.service.impl.pipeline.phases.PermitPlanAssemblyPhase;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The permit plan resolves its row-scope grade (D3) from the REAL {@link DataPermissionEngine}, not a
 * mock — so this proves the assumption the whole enforce flip rests on: a model with no row policy
 * resolves to {@code ALL} (unrestricted). If that were wrong, moving enforcement onto the plan would
 * silently over-restrict every model that has no policy. The unit tests can only mock the engine;
 * this runs the assembly phase against the real engine and a real bootstrapped tenant/member.
 *
 * @see PermitPlanAssemblyPhase
 * @see com.auraboot.framework.meta.service.impl.DataPermissionEngineImpl
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("Permit-plan scope resolution against the real data-permission engine")
class PermitPlanScopeResolutionIT extends BaseIntegrationTest {

    @Autowired private TenantBootstrapService tenantBootstrapService;
    @Autowired private UserPermissionService userPermissionService;
    @Autowired private DataPermissionEngine dataPermissionEngine;
    @Autowired private PermitPlanAssemblyPhase assemblyPhase;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;
    private Long memberUserId;
    private Long memberMemberId;

    /** A model no policy references — its scope must resolve to the unrestricted default. */
    private String unpolicedModel;

    @BeforeEach
    void setup() {
        tenantId = TestIdGenerator.uniqueTenantId();
        memberUserId = TestIdGenerator.uniqueUserId();
        unpolicedModel = "ab_permit_scope_probe_" + tenantId;

        jdbc.update("INSERT INTO ab_tenant (id, pid, name, status, deleted_flag) "
                        + "VALUES (?, ?, ?, 'active', FALSE) ON CONFLICT (id) DO NOTHING",
                tenantId, "tn_" + tenantId, "permit_scope_" + tenantId);
        memberMemberId = insertMember(memberUserId);

        TenantBootstrapService.BootstrapResult result =
                tenantBootstrapService.bootstrapTenant(tenantId, memberUserId);
        assertThat(result.isSuccess())
                .as("tenant bootstrap must succeed: %s", result.getMessage())
                .isTrue();
        userPermissionService.evictUserPermissions(memberUserId);
    }

    private Long insertMember(Long userId) {
        long memberId = System.nanoTime() & 0x7fffffffffffffffL;
        jdbc.update("INSERT INTO ab_tenant_member (id, pid, tenant_id, user_id, status, deleted_flag) "
                        + "VALUES (?, ?, ?, ?, 'active', FALSE)",
                memberId, UniqueIdGenerator.generate(), tenantId, userId);
        return memberId;
    }

    @AfterEach
    void cleanup() {
        if (tenantId != null) {
            jdbc.update("DELETE FROM ab_user_role WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_role_permission WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_permission WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_role WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_menu WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_tenant_member WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_tenant WHERE id = ?", tenantId);
        }
        if (memberUserId != null) userPermissionService.evictUserPermissions(memberUserId);
        MetaContext.clear();
    }

    /**
     * The real engine returns a blank filter for a model with no policy (the unrestricted default),
     * and the assembly phase must read that as {@code ALL}. This is the no-regression assumption of
     * the whole feature, proven against the real engine rather than a stub.
     */
    @Test
    @DisplayName("a model with no row policy resolves to ALL scope through the real engine")
    void unpolicedModelResolvesToAllScope() {
        MetaContext.setContext(tenantId, memberUserId, "u-" + memberUserId, "permit-scope");
        MetaContext.setMemberId(memberMemberId);
        try {
            // Direct engine check: no policy → blank filter (the unrestricted default).
            String rowFilter = dataPermissionEngine.buildRowFilter(tenantId, unpolicedModel, memberUserId);
            assertThat(rowFilter)
                    .as("the real engine applies no row filter to an unpoliced model")
                    .isNullOrEmpty();

            // Through the assembly phase: that blank must become ALL, not SELF.
            CommandPipelineContext ctx = permitContext(unpolicedModel);
            assemblyPhase.execute(ctx);

            assertThat(ctx.getPermitPlan().scope())
                    .as("an unpoliced model must resolve to ALL, or enforcing the plan would over-restrict it")
                    .isEqualTo(CommandPermitPlan.ScopeGrade.ALL);
        } finally {
            MetaContext.clear();
        }
    }

    private CommandPipelineContext permitContext(String modelCode) {
        CommandDefinition command = new CommandDefinition();
        command.setModelCode(modelCode);
        CommandPipelineContext ctx = CommandPipelineContext.builder()
                .commandCode("test:probe_scope")
                .request(new CommandExecuteRequest())
                .tenantId(tenantId)
                .userId(memberUserId)
                .startTime(System.currentTimeMillis())
                .build();
        ctx.setCommand(command);
        ctx.recordPhaseDecision(CommandPermitPlan.PhaseDecision.permit("authorization"));
        return ctx;
    }
}
