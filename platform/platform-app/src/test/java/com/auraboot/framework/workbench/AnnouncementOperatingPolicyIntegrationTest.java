package com.auraboot.framework.workbench;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.workbench.service.AnnouncementOperatingPolicyService;
import com.auraboot.framework.workbench.service.AnnouncementTenantPolicyService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.TestPropertySource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Actual tenant policy mapper, CAS, operation replay and immutable PostgreSQL audit. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties={"auraboot.announcement.worker-enabled=false","auraboot.announcement.approval.worker-enabled=false"})
class AnnouncementOperatingPolicyIntegrationTest extends BaseIntegrationTest {
    @Autowired AnnouncementOperatingPolicyService policies;
    @Autowired AnnouncementTenantPolicyService current;
    @Autowired JdbcTemplate jdbc;

    @BeforeAll void admitOwnedDatabaseBeforeFixtureWrites() {
        String expected=System.getenv("TEST_EXPECTED_DATABASE");
        assertThat(expected).isNotBlank().matches("[a-z][a-z0-9_]{0,62}");
        assertThat(expected).isEqualTo(System.getenv("POSTGRES_DB"));
        assertThat(jdbc.queryForObject("SELECT current_database()",String.class)).isEqualTo(expected);
    }
    @BeforeEach void grantPolicyPermission() {
        grantCommittedPermissionToTestRole("announcement.announcement.policy_manage","announcement",
            "announcement:announcement","policy_manage","Manage announcement policy");
        applyTestMetaContext();
    }
    @Test void defaultsCasReplayAndSecurityGenerationUseRealDatabaseContracts() {
        assertThat(current.current().revision()).isZero();
        String operation=UUID.randomUUID().toString();
        var input=Map.<String,Object>of("expectedPolicyRevision",0,"quietStart","22:00","quietEnd","08:00");
        var first=policies.execute(input,operation);
        assertThat(policies.execute(input,operation)).isEqualTo(first);
        assertThat(current.current().quietStart()).isEqualTo("22:00");
        assertThat(current.current().securityRevision()).isZero();
        policies.execute(Map.of("expectedPolicyRevision",1,"reminderAt","10:30"),UUID.randomUUID().toString());
        assertThat(current.current().revision()).isEqualTo(2);
        assertThat(current.current().securityRevision()).isZero();
        policies.execute(Map.of("expectedPolicyRevision",2,"forceApproval",true),UUID.randomUUID().toString());
        assertThat(current.current().securityRevision()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_announcement_policy_audit WHERE tenant_id=?",Integer.class,
            getTestTenant().getId())).isEqualTo(3);
        var audit=jdbc.queryForMap("SELECT previous_policy->>'forceApproval' AS before_value,resulting_policy->>'forceApproval' AS after_value FROM ab_announcement_policy_audit WHERE tenant_id=? AND resulting_revision=3",getTestTenant().getId());
        assertThat(audit).containsEntry("before_value","false").containsEntry("after_value","true");
    }
    @Test void staleRevisionAndForgedMembershipCannotWrite() {
        policies.execute(Map.of("expectedPolicyRevision",0),UUID.randomUUID().toString());
        MetaContext.setMemberId(getTestTenantMember().getId()+1);
        assertThatThrownBy(()->policies.execute(Map.of("expectedPolicyRevision",1),UUID.randomUUID().toString()))
            .isInstanceOf(AccessDeniedException.class);
        applyTestMetaContext();
        assertThatThrownBy(()->policies.execute(Map.of("expectedPolicyRevision",0),UUID.randomUUID().toString()))
            .isInstanceOf(com.auraboot.framework.exception.ConflictException.class);
    }
    @Test void acceptedPolicyAuditCannotBeRewrittenEvenByDirectSql() {
        policies.execute(Map.of("expectedPolicyRevision",0),UUID.randomUUID().toString());
        assertThatThrownBy(()->jdbc.update("UPDATE ab_announcement_policy_audit SET resulting_policy='{}'::jsonb WHERE tenant_id=?",
            getTestTenant().getId())).hasMessageContaining("Announcement policy audit is immutable");
    }
}
