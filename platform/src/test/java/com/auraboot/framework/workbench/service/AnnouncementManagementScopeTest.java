package com.auraboot.framework.workbench.service;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class AnnouncementManagementScopeTest {
    private final Map<String,Object> captured=Map.of("schema","announcement.organization.v1","captured",true,
        "departmentPids",List.of("published-dept"),"teamPids",List.of("published-team"));

    @Test void historicalScopeUsesFrozenClassificationInsteadOfCurrentRecipientMembership() {
        var scope=new AnnouncementManagementScope("dept_and_sub","manager",Set.of("published-dept"),Set.of());
        assertThat(scope.contains("former-member",captured)).isTrue();
        assertThat(scope.contains("new-member",Map.of("captured",false))).isFalse();
        assertThat(new AnnouncementManagementScope("dept","manager",Set.of("current-dept"),Set.of("former-member"))
            .contains("former-member",captured)).isFalse();
    }
    @Test void unknownHistoricalClassificationNeverGrantsDepartmentOrTeamAccess() {
        for(String type:List.of("dept","dept_and_sub","team","none","unknown"))
            assertThat(new AnnouncementManagementScope(type,"manager",Set.of("published-dept","published-team"),Set.of("former-member"))
                .contains("former-member",Map.of("captured",false))).isFalse();
    }
    @Test void unknownSchemaCannotBroadenHistoricalScope() {
        var unknown=new HashMap<>(captured);unknown.put("schema","announcement.organization.v2");
        assertThat(new AnnouncementManagementScope("dept","manager",Set.of("published-dept"),Set.of())
            .contains("former-member",unknown)).isFalse();
    }
    @Test void tenantWideAndSelfScopesDoNotInventOrganizationFacts() {
        var all=new AnnouncementManagementScope("all","manager",Set.of(),Set.of());
        var self=new AnnouncementManagementScope("self","manager",Set.of(),Set.of());
        assertThat(all.contains("former-member",Map.of("captured",false))).isTrue();
        assertThat(self.contains("manager",Map.of("captured",false))).isTrue();
        assertThat(self.contains("former-member",captured)).isFalse();
    }
    @Test void teamAuthorityMatchesFrozenTeamAndDefensivelyCopiesItsInputs() {
        Set<String> teams=new HashSet<>(Set.of("published-team"));
        var scope=new AnnouncementManagementScope("team","manager",teams,Set.of());
        teams.clear();
        assertThat(scope.contains("former-member",captured)).isTrue();
        assertThat(new AnnouncementManagementScope("team","manager",Set.of("other"),Set.of()).contains("former-member",captured)).isFalse();
    }
}
