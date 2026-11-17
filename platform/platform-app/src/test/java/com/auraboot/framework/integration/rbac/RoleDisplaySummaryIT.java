package com.auraboot.framework.integration.rbac;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.permission.service.RoleAuthorizationAuthoringAccess;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** Real SQL summaries remain tenant-scoped and cannot become management authority. */
class RoleDisplaySummaryIT extends BaseIntegrationTest {
    @Autowired RoleService roles;
    @Autowired JdbcTemplate jdbc;
    @Autowired RoleAuthorizationAuthoringAccess authoring;

    @Test void countsAssignedMembersEvenWhenTheirAssignmentWindowHasExpired() {
        var tenant = getTestTenant().getId();
        var role = getTestRole().getId();
        assertThat(roles.displaySummaries(tenant, List.of(role))).singleElement()
                .satisfies(summary -> assertThat(summary.getMemberCount()).isEqualTo(1L));
        jdbc.update("UPDATE ab_user_role SET expiry_date=CURRENT_DATE-1 WHERE tenant_id=? AND role_id=? AND member_id=?",
                tenant, role, getTestTenantMember().getId());
        assertThat(roles.displaySummaries(tenant, List.of(role))).singleElement()
                .satisfies(summary -> assertThat(summary.getMemberCount()).isEqualTo(1L));
    }

    @Test void rejectsForeignTenantArgumentsAndNeverReturnsForeignRoles() {
        var tenant = getTestTenant().getId();
        assertThatThrownBy(() -> roles.displaySummaries(tenant + 1, List.of(getTestRole().getId())))
                .isInstanceOf(AccessDeniedException.class);
        MetaContext.setContext(tenant + 1, getTestUser().getId(), getTestUser().getPid(), "summary-isolation");
        assertThat(roles.displaySummaries(tenant + 1, List.of(getTestRole().getId()))).isEmpty();
    }

    @Test void aManagementDisplayGroupDoesNotGrantTenantAdministratorQualification() {
        grantCommittedPermissionToTestRole("org.role.update", "API", "role", "update", "Role summary fixture");
        assertThat(roles.displaySummaries(getTestTenant().getId(), List.of(getTestRole().getId())))
                .singleElement().satisfies(summary -> assertThat(summary.getManagementRole()).isTrue());
        assertThatThrownBy(() -> authoring.requireWrite()).isInstanceOf(AccessDeniedException.class);
    }
    @Test void absentTenantContextCannotReadEvenAnEmptySummary() {
        MetaContext.clear();
        assertThatThrownBy(() -> roles.displaySummaries(null, List.of()))
                .isInstanceOf(AccessDeniedException.class);
    }
}
