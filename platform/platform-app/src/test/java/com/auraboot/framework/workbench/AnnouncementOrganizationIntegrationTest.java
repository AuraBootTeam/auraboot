package com.auraboot.framework.workbench;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.service.ActivityService;
import com.auraboot.framework.workbench.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Actual publication and estimate persistence; historical classification never follows directory edits. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties={"aura.notification.plugin-delivery.enabled=true",
    "auraboot.announcement.worker-enabled=false","auraboot.announcement.approval.worker-enabled=false"})
class AnnouncementOrganizationIntegrationTest extends BaseIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired AnnouncementLifecycleService lifecycle;
    @Autowired AnnouncementReleaseService releases;
    @Autowired AnnouncementReaderService reader;
    @Autowired AnnouncementCodec codec;
    @Autowired ActivityService activities;

    @BeforeAll void admitOwnedDatabaseBeforeFixtureWrites() {
        String expected=System.getenv("TEST_EXPECTED_DATABASE");
        assertThat(expected).isNotBlank().matches("[a-z][a-z0-9_]{0,62}");
        assertThat(expected).isEqualTo(System.getenv("POSTGRES_DB"));
        assertThat(jdbc.queryForObject("SELECT current_database()",String.class)).isEqualTo(expected);
    }
    @BeforeEach void grants() {
        for(String action:List.of("edit","publish","publish_all","exempt"))
            grantCommittedPermissionToTestRole("announcement.announcement."+action,"announcement",
                "announcement:announcement",action,"Announcement "+action);
        applyTestMetaContext();
    }
    @Test
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void submissionEstimateAndActualPublicationCaptureDifferentDirectoryInstantsAndRemainImmutable() {
        Long tenant=getTestTenant().getId();String first=UniqueIdGenerator.generate(),second=UniqueIdGenerator.generate();
        String position=UniqueIdGenerator.generate(),employee=UniqueIdGenerator.generate(),operation=UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO mt_org_department(pid,tenant_id,org_dept_name,org_dept_status) VALUES(?,?,'Original department','active'),(?,?,'Publication department','active')",first,tenant,second,tenant);
        jdbc.update("INSERT INTO mt_org_position(pid,tenant_id,org_pos_name,org_pos_dept_id,org_pos_level) VALUES(?,?,'Staff',?,'1')",position,tenant,first);
        jdbc.update("INSERT INTO mt_org_employee(pid,tenant_id,org_emp_name,org_emp_dept_id,org_emp_position_id,org_emp_member_id,org_emp_status) VALUES(?,?,'Employee',?,?,?,'active')",
            employee,tenant,first,position,getTestTenantMember().getPid());
        var policy=Map.of("audience",Map.of("kind","all"),"exemptionReason","Organization snapshot verification","acknowledgementRequired",true);
        String pid=(String)lifecycle.execute("announcement:create_announcement",null,
            Map.of("title","Organization "+operation,"content","Immutable recipient classification","policy",policy),operation,null).get("pid");
        var accepted=releases.execute("announcement:publish",pid,Map.of(),operation+"-publish",1);
        String version=(String)accepted.get("versionPid");
        var estimate=codec.object(jdbc.queryForObject("SELECT organization_snapshot::text FROM ab_announcement_audience_estimate WHERE tenant_id=? AND version_pid=? AND user_pid=?",
            String.class,tenant,version,getTestUser().getPid()));
        assertThat(estimate.get("departments")).isEqualTo(List.of(Map.of("pid",first,"label","Original department")));
        jdbc.update("UPDATE mt_org_employee SET org_emp_dept_id=? WHERE tenant_id=? AND pid=?",second,tenant,employee);
        MetaContext.clear();var job=releases.claim();assertThat(job).isNotNull();
        assertThat(job.get("version_pid")).isEqualTo(version);releases.perform(job);applyTestMetaContext();
        String snapshot=jdbc.queryForObject("SELECT organization_snapshot::text FROM ab_announcement_recipient WHERE tenant_id=? AND version_pid=? AND user_pid=?",
            String.class,tenant,version,getTestUser().getPid());
        var captured=codec.object(snapshot);
        assertThat(captured).containsEntry("captured",true);
        assertThat(captured.get("departments")).isEqualTo(List.of(Map.of("pid",second,"label","Publication department")));
        jdbc.update("UPDATE mt_org_department SET org_dept_name='Renamed later' WHERE tenant_id=? AND pid=?",tenant,second);
        assertThat(jdbc.queryForObject("SELECT organization_snapshot::text FROM ab_announcement_recipient WHERE tenant_id=? AND version_pid=? AND user_pid=?",
            String.class,tenant,version,getTestUser().getPid())).isEqualTo(snapshot);
        assertThatThrownBy(()->jdbc.update("UPDATE ab_announcement_recipient SET organization_snapshot='{}'::jsonb WHERE tenant_id=? AND version_pid=?",tenant,version))
            .hasMessageContaining("Announcement recipient identity is immutable");
        assertThatThrownBy(()->jdbc.update("UPDATE ab_announcement_recipient_snapshot SET recipient_count=0 WHERE tenant_id=? AND version_pid=?",tenant,version))
            .hasMessageContaining("Sealed announcement audience is immutable");
        assertThatThrownBy(()->jdbc.update("UPDATE ab_announcement_audience_estimate SET organization_snapshot='{}'::jsonb WHERE tenant_id=? AND version_pid=?",tenant,version))
            .hasMessageContaining("Submission audience estimates are immutable");
        var detail=reader.detail(version);assertThat(detail).doesNotContainKeys("organizationSnapshot","organizationSnapshotJson");
        String hash=(String)detail.get("snapshotHash");
        assertThat(reader.mark(version,Map.of("snapshotHash",hash),false).get("readState")).isEqualTo("read");
        assertThat(reader.mark(version,Map.of("snapshotHash",hash),true).get("acknowledgementState")).isEqualTo("completed");
    }
    @Test void genericActivityRetainsUuidPublicRecordTargets() {
        String target=UUID.randomUUID().toString();
        var activity=activities.recordSystemActivity(getTestTenant().getId(),"ab_announcement",target,"state_change",
            "Announcement withdrawn","announcement:archive","state_transition",getTestUser().getId(),"Operator",null);
        assertThat(activity.getObjectRecord()).isEqualTo(target);
        assertThat(activities.getActivities(getTestTenant().getId(),"ab_announcement",target,10))
            .extracting(com.auraboot.framework.meta.entity.Activity::getObjectRecord).containsExactly(target);
    }
}
