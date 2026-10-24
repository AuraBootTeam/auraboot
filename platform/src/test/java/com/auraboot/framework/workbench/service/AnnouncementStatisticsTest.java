package com.auraboot.framework.workbench.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AnnouncementStatisticsTest {
    private static final Instant DUE=Instant.parse("2026-10-20T10:00:00Z");
    private static final AnnouncementManagementScope ALL=new AnnouncementManagementScope("all","actor",Set.of(),Set.of());
    private static final Map<String,Object> ORGANIZATION=Map.of("schema","announcement.organization.v1","captured",true,
        "departmentPids",List.of("sales","private"),"departments",List.of(Map.of("pid","sales","label","Sales at publication"),
        Map.of("pid","private","label","Private classification")),"matchedRoles",List.of(Map.of("pid","reviewers","label","Selected reviewer role")));
    private AnnouncementStatistics.Fact fact(String user,Instant read,Instant ack,boolean eligible) {
        return new AnnouncementStatistics.Fact(user,"member-"+user,"Name "+user,ORGANIZATION,read,ack,eligible,"completed");
    }
    @Test void inactiveMembersRemainInHistoricalDenominatorsAndLateCompletionIsNotOverdue() {
        var capture=AnnouncementStatistics.capture(List.of(fact("one",DUE.minusSeconds(20),DUE.plusSeconds(1),false),
            fact("two",null,null,true),fact("three",DUE.minusSeconds(20),null,false)),"publication",true,DUE,true,DUE.plusSeconds(30),ALL,AnnouncementStatistics.Filters.all());
        var summary=capture.summary();
        assertThat(summary.actualRecipients()).isEqualTo(3);assertThat(summary.read()).isEqualTo(2);assertThat(summary.acknowledged()).isEqualTo(1);
        assertThat(summary.inactiveMembers()).isEqualTo(2);assertThat(summary.overdueUnacknowledged()).isEqualTo(2);
        assertThat(summary.lateCompleted()).isEqualTo(1);assertThat(summary.actionable()).isEqualTo(1);
        assertThat(summary.readRate()).isEqualTo(66.67);assertThat(summary.acknowledgementRate()).isEqualTo(33.33);
        assertThat(capture.rows().getFirst().overdue()).isFalse();
    }
    @Test void detailFiltersDoNotRewriteTheHistoricalDenominator() {
        var result=AnnouncementStatistics.capture(List.of(fact("one",DUE,null,true),fact("two",null,null,true)),"publication",false,null,true,DUE,ALL,
            new AnnouncementStatistics.Filters("two","unread",null));
        assertThat(result.summary().actualRecipients()).isEqualTo(2);assertThat(result.summary().filteredRows()).isEqualTo(1);
        assertThat(result.summary().acknowledged()).isNull();assertThat(result.summary().acknowledgementRate()).isNull();
        assertThat(result.rows()).filteredOn(AnnouncementStatistics.Row::matchesFilters).extracting(AnnouncementStatistics.Row::pid).containsExactly("two");
    }
    @Test void legacyMigrationCohortDoesNotInventHistoricalSendingOrUnreadFacts() {
        var result=AnnouncementStatistics.capture(List.of(fact("one",DUE,null,true),fact("two",null,null,true)),"migration_cohort",false,null,true,DUE,ALL,AnnouncementStatistics.Filters.all());
        assertThat(result.summary().actualRecipients()).isNull();assertThat(result.summary().read()).isNull();
        assertThat(result.summary().readsAfterMigration()).isEqualTo(1);assertThat(result.summary().actionable()).isZero();
        assertThat(result.rows()).extracting(AnnouncementStatistics.Row::readState).containsExactly("read_after_migration","unknown");
    }
    @Test void scopeUsesFrozenClassificationAndOnlyPermittedLabelsEnterThePublicView() throws Exception {
        var scope=new AnnouncementManagementScope("dept","actor",Set.of("sales"),Set.of());
        var result=AnnouncementStatistics.capture(List.of(fact("moved-user",null,null,false)),"publication",true,DUE,true,DUE,scope,AnnouncementStatistics.Filters.all());
        assertThat(result.summary().actualRecipients()).isEqualTo(1);assertThat(result.rows().getFirst().departments()).containsExactly("Sales at publication");
        var json=new ObjectMapper().findAndRegisterModules().writeValueAsString(result.rows().getFirst());
        assertThat(json).doesNotContain("Private classification","departmentPids","memberPid","organization","displayedDepartmentPids","reviewers");
        assertThat(json).contains("Selected reviewer role");
    }
    @Test void exactDeadlineIsNotLateAndInactiveVersionsCannotBeReminded() {
        var result=AnnouncementStatistics.capture(List.of(fact("one",DUE.minusSeconds(1),DUE,true),fact("two",null,null,true)),"publication",true,DUE,false,DUE,ALL,AnnouncementStatistics.Filters.all());
        assertThat(result.summary().overdueUnacknowledged()).isZero();assertThat(result.summary().lateCompleted()).isZero();
        assertThat(result.summary().actionable()).isZero();
    }
    @Test void emptyDenominatorsDoNotReturnAnInventedPercentage() {
        var result=AnnouncementStatistics.capture(List.of(),"publication",true,DUE,true,DUE,ALL,AnnouncementStatistics.Filters.all());
        assertThat(result.summary().actualRecipients()).isZero();assertThat(result.summary().readRate()).isNull();assertThat(result.summary().acknowledgementRate()).isNull();
    }
}
