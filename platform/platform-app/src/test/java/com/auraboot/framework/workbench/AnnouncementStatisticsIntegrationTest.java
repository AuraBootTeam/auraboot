package com.auraboot.framework.workbench;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.workbench.mapper.AnnouncementStatisticsMapper;
import com.auraboot.framework.workbench.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** Actual authorized HTTP capture, immutable JSONB persistence and current-permission reauthorization. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.springframework.context.annotation.Import(AnnouncementStatisticsIntegrationTest.ReminderTimeConfiguration.class)
@TestPropertySource(properties={"aura.notification.plugin-delivery.enabled=true","auraboot.announcement.worker-enabled=false",
    "auraboot.announcement.approval.worker-enabled=false"})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class AnnouncementStatisticsIntegrationTest extends BaseIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired AnnouncementLifecycleService lifecycle;
    @Autowired AnnouncementReleaseService releases;
    @Autowired AnnouncementReaderService readers;
    @Autowired AnnouncementStatisticsService statistics;
    @Autowired AnnouncementStatisticsExportSource exports;
    @Autowired com.auraboot.framework.i18n.service.I18nResourceService translationResources;
    @Autowired com.auraboot.framework.i18n.service.I18nService messages;
    @Autowired com.auraboot.framework.meta.service.impl.DomainExportTaskService exportTasks;
    @Autowired com.auraboot.framework.meta.service.impl.ExportTaskService legacyExports;
    @Autowired com.auraboot.framework.meta.mapper.ExportTaskMapper exportRegistry;
    @Autowired com.auraboot.module.exchange.service.ExecutionJobQueryService executionJobs;
    @Autowired com.auraboot.framework.infrastructure.storage.StorageProperties storageProperties;
    @Autowired AnnouncementStatisticsExportCommand exportCommand;
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    static class ReminderTimeConfiguration {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        ControlledReminderClock controlledReminderClock() {return new ControlledReminderClock();}
    }
    static class ControlledReminderClock extends AnnouncementReminderClock {
        volatile Instant instant;
        @Override public Instant now() {return instant==null ? Instant.now() : instant;}
    }
    @Autowired ControlledReminderClock reminderClock;
    @Autowired AnnouncementReminderService reminders;
    @Autowired com.auraboot.framework.workbench.mapper.AnnouncementReminderMapper reminderPlans;
    @Autowired com.auraboot.framework.meta.service.impl.OutboxWorkerImpl outboxWorker;
    @Autowired com.auraboot.framework.meta.service.impl.AsyncTaskServiceImpl backgroundTasks;
    @Autowired AnnouncementStatisticsMapper mapper;
    @Autowired AnnouncementCodec codec;
    @Autowired UserPermissionService permissions;
    @Autowired com.auraboot.framework.tenant.service.TenantMemberService memberships;
    @Autowired com.auraboot.framework.rbac.service.UserRoleService roles;
    @Autowired PlatformTransactionManager transactions;
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    MockMvc http;

    @BeforeAll void admitOwnedDatabase() {
        String expected=System.getenv("TEST_EXPECTED_DATABASE");
        assertThat(expected).isNotBlank().isEqualTo(System.getenv("POSTGRES_DB"));
        assertThat(jdbc.queryForObject("SELECT current_database()",String.class)).isEqualTo(expected);
    }
    @BeforeEach void grantAndAuthenticate() {
        for(String action:List.of("read","edit","publish","publish_all","exempt","stats","export","withdraw","remind"))
            grantCommittedPermissionToTestRole("announcement.announcement."+action,"announcement","announcement:announcement",action,"Announcement "+action);
        applyTestMetaContext();
        var authorities=AuthorityUtils.createAuthorityList("role_admin");
        var user=new CustomUserDetails(getTestUser().getUserName(),"test-password",getTestUser().getId(),getTestUser().getPid(),authorities,true,true,true,true);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,authorities));
        http=MockMvcBuilders.webAppContextSetup(context).build();
    }
    @AfterEach void clearAuthentication(){reminderClock.instant=null;SecurityContextHolder.clearContext();}
    private String publish() {
        String operation=UniqueIdGenerator.generate();
        var policy=Map.of("audience",Map.of("kind","selected","userPids",List.of(getTestUser().getPid())),
            "exemptionReason","Statistics integration verification","acknowledgementRequired",true);
        String pid=(String)lifecycle.execute("announcement:create_announcement",null,Map.of("title","Statistics "+operation,
            "content","Read this version independently of statistics.","policy",policy),operation,null).get("pid");
        var accepted=releases.execute("announcement:publish",pid,Map.of(),operation+"-publish",1);
        MetaContext.clear();new AnnouncementReleaseWorker(releases).tick();applyTestMetaContext();
        assertThat(jdbc.queryForObject("SELECT status FROM ab_announcement WHERE tenant_id=? AND pid=?",String.class,getTestTenant().getId(),pid)).isEqualTo("published");
        return (String)accepted.get("versionPid");
    }
    @Test void publishedPinningChangesOnlyRootOrderingAndRetainsFrozenPublicationFacts() {
        String older=publish(),record=recordOf(older);publish();
        Long tenant=getTestTenant().getId();
        var frozen=jdbc.queryForMap("SELECT snapshot_hash,snapshot::text AS snapshot FROM ab_announcement_version WHERE tenant_id=? AND pid=?",tenant,older);
        var root=jdbc.queryForMap("SELECT row_version,editor_revision FROM ab_announcement WHERE tenant_id=? AND pid=?",tenant,record);
        long deliveries=jdbc.queryForObject("SELECT count(*) FROM ab_announcement_delivery WHERE tenant_id=? AND version_pid=?",Long.class,tenant,older);
        int revision=((Number)root.get("row_version")).intValue();String operation=UniqueIdGenerator.generate();
        var result=lifecycle.execute(AnnouncementLifecycleService.PIN,record,Map.of("pinned",true),operation,revision);
        assertThat(result).containsEntry("status","published").containsEntry("pinned",true).containsEntry("row_version",revision+1);
        assertThat(lifecycle.execute(AnnouncementLifecycleService.PIN,record,Map.of("pinned",true),operation,revision)).isEqualTo(result);
        assertThat(readers.list("","all",1,100).get("records")).asList().first().isInstanceOfSatisfying(Map.class,row->assertThat(row).containsEntry("versionPid",older).containsEntry("pinned",true));
        assertThat(jdbc.queryForMap("SELECT snapshot_hash,snapshot::text AS snapshot FROM ab_announcement_version WHERE tenant_id=? AND pid=?",tenant,older)).isEqualTo(frozen);
        assertThat(jdbc.queryForObject("SELECT editor_revision FROM ab_announcement WHERE tenant_id=? AND pid=?",Integer.class,tenant,record)).isEqualTo(root.get("editor_revision"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_announcement_delivery WHERE tenant_id=? AND version_pid=?",Long.class,tenant,older)).isEqualTo(deliveries);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_announcement_audit WHERE tenant_id=? AND announcement_pid=? AND command_code='announcement:set_pinned'",Long.class,tenant,record)).isEqualTo(1L);
        assertThatThrownBy(()->lifecycle.execute(AnnouncementLifecycleService.PIN,record,Map.of("pinned",false),UniqueIdGenerator.generate(),revision)).isInstanceOf(com.auraboot.framework.exception.ConflictException.class);
        lifecycle.execute(AnnouncementLifecycleService.PIN,record,Map.of("pinned",false),UniqueIdGenerator.generate(),revision+1);
        assertThat(readers.detail(older)).containsEntry("pinned",false);
    }

    @Test void capabilitiesExposeRealLimitsAndReadinessRequiresBothActionsWithoutInventingABpmProvider() throws Exception {
        http.perform(get("/api/announcements/capabilities")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.attachments.maxCount").value(10))
            .andExpect(jsonPath("$.data.channels[1].ready").value(false));
        boolean original=permissions.hasPermission(getTestUser().getId(),"announcement.announcement.submit");
        try {
            removeOwnSubmitGrant();
            http.perform(get("/api/announcements/approval-readiness")).andExpect(status().isForbidden());
            grantCommittedPermissionToTestRole("announcement.announcement.submit","announcement","announcement:announcement","submit","Announcement submit");
            permissions.evictRoleUsers(getTestTenant().getId(),getTestRole().getId());
            http.perform(get("/api/announcements/approval-readiness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ready").value(false))
                .andExpect(jsonPath("$.data.reasonCode").value("APPROVAL_PROVIDER_UNAVAILABLE"));
        } finally {
            if(original) grantCommittedPermissionToTestRole("announcement.announcement.submit","announcement","announcement:announcement","submit","Announcement submit");
            else removeOwnSubmitGrant();
            permissions.evictRoleUsers(getTestTenant().getId(),getTestRole().getId());
        }
    }

    private void removeOwnSubmitGrant() {
        jdbc.update("DELETE FROM ab_role_permission WHERE tenant_id=? AND role_id=? AND permission_id IN (SELECT id FROM ab_permission WHERE tenant_id=? AND code='announcement.announcement.submit')",
            getTestTenant().getId(),getTestRole().getId(),getTestTenant().getId());
        permissions.evictRoleUsers(getTestTenant().getId(),getTestRole().getId());
    }

    @Test void disablingTheDeliveryBridgeAfterAcceptancePreventsPublicationAtExecution() {
        String operation=UniqueIdGenerator.generate();
        String record=(String)lifecycle.execute("announcement:create_announcement",null,Map.of("title","Delivery bridge "+operation,
            "content","An accepted release must recheck its live channel capability.","policy",Map.of(
                "audience",Map.of("kind","selected","userPids",List.of(getTestUser().getPid())),"exemptionReason","Bridge execution verification")),operation,null).get("pid");
        String version=(String)releases.execute(AnnouncementReleaseService.PUBLISH,record,Map.of(),operation+"-publish",1).get("versionPid");
        Map<String,Object> claim=null;
        for(int index=0;index<100;index++) {
            var candidate=releases.claim();if(candidate==null) break;
            if(version.equals(candidate.get("version_pid"))) {claim=candidate;break;}
            releases.perform(candidate);
        }
        assertThat(claim).as("The exact owned release must be claimed").isNotNull();
        var ownedClaim=claim;
        Object original=org.springframework.test.util.ReflectionTestUtils.getField(releases,"deliveryEnabled");
        try {
            org.springframework.test.util.ReflectionTestUtils.setField(releases,"deliveryEnabled",false);
            assertThatThrownBy(()->releases.perform(ownedClaim)).isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
            assertThat(jdbc.queryForObject("SELECT publication_state FROM ab_announcement_version_control WHERE tenant_id=? AND version_pid=?",String.class,getTestTenant().getId(),version)).isEqualTo("unpublished");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_announcement_recipient WHERE tenant_id=? AND version_pid=?",Long.class,getTestTenant().getId(),version)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_announcement_delivery WHERE tenant_id=? AND version_pid=?",Long.class,getTestTenant().getId(),version)).isZero();
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(releases,"deliveryEnabled",original);
            applyTestMetaContext();
            var root=jdbc.queryForMap("SELECT status,row_version FROM ab_announcement WHERE tenant_id=? AND pid=?",getTestTenant().getId(),record);
            if("published".equals(root.get("status"))) lifecycle.execute("announcement:archive",record,Map.of(),UniqueIdGenerator.generate(),((Number)root.get("row_version")).intValue());
            else releases.execute(AnnouncementReleaseService.CANCEL,record,Map.of(),UniqueIdGenerator.generate(),((Number)root.get("row_version")).intValue());
        }
    }

    private Map<String,Object> acceptRecoveryFixture() {
        String operation=UniqueIdGenerator.generate();
        String record=(String)lifecycle.execute("announcement:create_announcement",null,Map.of("title","Recovery "+operation,
            "content","Recover only this original frozen release.","policy",Map.of("audience",Map.of("kind","selected",
                "userPids",List.of(getTestUser().getPid())),"exemptionReason","Publication recovery verification")),operation,null).get("pid");
        return releases.execute(AnnouncementReleaseService.PUBLISH,record,Map.of(),operation+"-publish",1);
    }

    private Map<String,Object> claimRecoveryFixture(String version) {
        for(int index=0;index<100;index++) {
            var claim=releases.claim();if(claim==null) break;
            if(version.equals(claim.get("version_pid"))) return claim;
            releases.perform(claim);
        }
        throw new AssertionError("The exact owned recovery fixture was not claimed");
    }

    @Test void publicationRecoveryFencesOldWorkersAndKeepsTheOriginalVersionActorAndWindow() throws Exception {
        var accepted=acceptRecoveryFixture();String record=(String)accepted.get("recordPid"),version=(String)accepted.get("versionPid");
        Long tenant=getTestTenant().getId();var stale=claimRecoveryFixture(version);
        releases.fail(stale,"blocked","DELIVERY_DISABLED");
        var original=jdbc.queryForMap("SELECT pid,actor_user_id,actor_member_pid,authorization_hash,scheduled_at,latest_allowed_at,attempts FROM ab_announcement_release WHERE tenant_id=? AND version_pid=?",tenant,version);
        String hash=jdbc.queryForObject("SELECT snapshot_hash FROM ab_announcement_version WHERE tenant_id=? AND pid=?",String.class,tenant,version);
        http.perform(get("/api/announcements/records/"+record+"/publication")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.records[0].canRetry").value(true))
            .andExpect(jsonPath("$.data.records[0].errorCode").value("DELIVERY_DISABLED"));
        String operation=UniqueIdGenerator.generate();
        var retried=releases.execute(AnnouncementReleaseService.RETRY,record,Map.of(),operation,2);
        assertThat(retried).containsEntry("releaseAttemptPid",accepted.get("releaseAttemptPid")).containsEntry("row_version",3).containsEntry("status","unpublished");
        assertThat(releases.execute(AnnouncementReleaseService.RETRY,record,Map.of(),operation,2)).isEqualTo(retried);
        assertThat(jdbc.queryForMap("SELECT pid,actor_user_id,actor_member_pid,authorization_hash,scheduled_at,latest_allowed_at,attempts FROM ab_announcement_release WHERE tenant_id=? AND version_pid=?",tenant,version)).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT fencing_token FROM ab_announcement_release WHERE tenant_id=? AND version_pid=?",Long.class,tenant,version)).isGreaterThan(((Number)stale.get("fencing_token")).longValue());
        releases.perform(stale);releases.fail(stale,"blocked","LATE_FAILURE");
        assertThat(jdbc.queryForObject("SELECT status FROM ab_announcement_release WHERE tenant_id=? AND version_pid=?",String.class,tenant,version)).isEqualTo("queued");
        var current=claimRecoveryFixture(version);assertThat(current.get("pid")).isEqualTo(stale.get("pid"));releases.perform(current);
        assertThat(jdbc.queryForObject("SELECT status FROM ab_announcement WHERE tenant_id=? AND pid=?",String.class,tenant,record)).isEqualTo("published");
        assertThat(jdbc.queryForObject("SELECT snapshot_hash FROM ab_announcement_version WHERE tenant_id=? AND pid=?",String.class,tenant,version)).isEqualTo(hash);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_announcement_audit WHERE tenant_id=? AND announcement_pid=? AND command_code='announcement:retry_publication'",Long.class,tenant,record)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT attempts FROM ab_announcement_release WHERE tenant_id=? AND version_pid=?",Integer.class,tenant,version)).isEqualTo(2);
    }

    @Test void canceledAndOutOfWindowPublicationTasksCannotBeReopened() {
        var accepted=acceptRecoveryFixture();String record=(String)accepted.get("recordPid"),version=(String)accepted.get("versionPid");
        releases.fail(claimRecoveryFixture(version),"blocked","EXECUTION_FAILURE");
        releases.execute(AnnouncementReleaseService.CANCEL,record,Map.of(),UniqueIdGenerator.generate(),2);
        assertThatThrownBy(()->releases.execute(AnnouncementReleaseService.RETRY,record,Map.of(),UniqueIdGenerator.generate(),3)).isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
        var late=acceptRecoveryFixture();String lateRecord=(String)late.get("recordPid"),lateVersion=(String)late.get("versionPid");
        releases.fail(claimRecoveryFixture(lateVersion),"blocked","EXECUTION_FAILURE");
        jdbc.update("UPDATE ab_announcement_release SET scheduled_at=clock_timestamp()-INTERVAL '2 seconds',latest_allowed_at=clock_timestamp()-INTERVAL '1 second' WHERE tenant_id=? AND version_pid=?",getTestTenant().getId(),lateVersion);
        assertThat(((List<Map<String,Object>>)releases.summary(lateRecord).get("records")).getFirst()).containsEntry("canRetry",false);
        assertThatThrownBy(()->releases.execute(AnnouncementReleaseService.RETRY,lateRecord,Map.of(),UniqueIdGenerator.generate(),2)).isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_announcement_recipient WHERE tenant_id=? AND version_pid=?",Long.class,getTestTenant().getId(),lateVersion)).isZero();
        releases.execute(AnnouncementReleaseService.CANCEL,lateRecord,Map.of(),UniqueIdGenerator.generate(),2);
    }

    @Test void publicationStatusMasksPrivateDiagnosticsAndReadOnlyAuthorityCannotResume() throws Exception {
        var accepted=acceptRecoveryFixture();String record=(String)accepted.get("recordPid"),version=(String)accepted.get("versionPid");
        releases.fail(claimRecoveryFixture(version),"blocked","private storage diagnostic");
        try {
            jdbc.update("DELETE FROM ab_role_permission WHERE tenant_id=? AND role_id=? AND permission_id IN (SELECT id FROM ab_permission WHERE tenant_id=? AND code='announcement.announcement.publish')",
                getTestTenant().getId(),getTestRole().getId(),getTestTenant().getId());
            permissions.evictRoleUsers(getTestTenant().getId(),getTestRole().getId());
            String body=http.perform(get("/api/announcements/records/"+record+"/publication")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].canRetry").value(false))
                .andExpect(jsonPath("$.data.records[0].errorCode").value("PUBLICATION_BLOCKED")).andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("private storage diagnostic","actor_user_id","authorization_hash","fencing_token");
            assertThatThrownBy(()->releases.execute(AnnouncementReleaseService.RETRY,record,Map.of(),UniqueIdGenerator.generate(),2)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        } finally {
            grantCommittedPermissionToTestRole("announcement.announcement.publish","announcement","announcement:announcement","publish","Announcement publish");
            permissions.evictRoleUsers(getTestTenant().getId(),getTestRole().getId());
            releases.execute(AnnouncementReleaseService.CANCEL,record,Map.of(),UniqueIdGenerator.generate(),2);
        }
    }

    private String recordOf(String version) {
        return jdbc.queryForObject("SELECT announcement_pid FROM ab_announcement_version WHERE tenant_id=? AND pid=?",String.class,getTestTenant().getId(),version);
    }
    @Test void actualReminderCreatesOneIndependentNotificationWithoutDuplicatingAcknowledgement() throws Exception {
        String version=publish(),record=recordOf(version);
        var preview=reminders.preview(record,version,AnnouncementStatistics.Filters.all());
        assertThat(preview.get("eligible")).isEqualTo(1L);
        String operation=UniqueIdGenerator.generate();var payload=Map.<String,Object>of("previewToken",preview.get("previewToken"));
        var accepted=reminders.execute(AnnouncementReminderService.SUBMIT,record,payload,operation);
        assertThat(accepted.get("actualCount")).isEqualTo(1);assertThat(accepted).doesNotContainKey("actorMemberPid");
        assertThat(reminders.execute(AnnouncementReminderService.SUBMIT,record,payload,operation)).isEqualTo(accepted);
        String batch=(String)accepted.get("batchPid");
        org.awaitility.Awaitility.await().pollInSameThread().atMost(Duration.ofSeconds(15)).untilAsserted(()->{
            outboxWorker.pollAndDispatch();applyTestMetaContext();
            assertThat(jdbc.queryForObject("SELECT status FROM ab_announcement_delivery WHERE tenant_id=? AND batch_pid=? AND user_pid=?",String.class,
                getTestTenant().getId(),batch,getTestUser().getPid())).isEqualTo("completed");});
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_notification WHERE tenant_id=? AND user_id=? AND source_type='announcement' AND source_id=?",Long.class,
            getTestTenant().getId(),getTestUser().getId(),version)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_inbox_item WHERE tenant_id=? AND user_id=? AND item_type='announcement_ack' AND source_id=?",Long.class,
            getTestTenant().getId(),getTestUser().getId(),version)).isEqualTo(1L);
        var second=reminders.preview(record,version,new AnnouncementStatistics.Filters("","unread",null));
        assertThat(second.get("eligible")).isEqualTo(0L);assertThat(second.get("throttled")).isEqualTo(1L);
        assertThat(reminders.previewStatus((String)second.get("previewToken"))).containsEntry("status","throttled");
        var skipped=reminders.execute(AnnouncementReminderService.SUBMIT,record,Map.of("previewToken",second.get("previewToken")),UniqueIdGenerator.generate());
        assertThat(skipped.get("actualCount")).isEqualTo(0);assertThat(skipped.get("operationState")).isEqualTo("skipped");
        assertThat(jdbc.queryForObject("SELECT automatic_batches FROM ab_announcement_reminder_plan WHERE tenant_id=? AND version_pid=?",Integer.class,
            getTestTenant().getId(),version)).isZero();
    }
    @Test void reminderRecalculatesPreviewAndSkipsWorkCompletedBeforeAcceptance() {
        String version=publish(),record=recordOf(version);var preview=reminders.preview(record,version,AnnouncementStatistics.Filters.all());
        var detail=readers.detail(version);var mark=Map.<String,Object>of("snapshotHash",detail.get("snapshotHash"));
        readers.mark(version,mark,false);readers.mark(version,mark,true);
        var result=reminders.execute(AnnouncementReminderService.SUBMIT,record,Map.of("previewToken",preview.get("previewToken")),UniqueIdGenerator.generate());
        assertThat(result.get("previewCount")).isEqualTo(1);assertThat(result.get("actualCount")).isEqualTo(0);
        var fresh=reminders.preview(record,version,AnnouncementStatistics.Filters.all());
        assertThat(reminders.previewStatus((String)fresh.get("previewToken"))).containsEntry("status","empty");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_announcement_delivery WHERE tenant_id=? AND batch_pid=?",Long.class,
            getTestTenant().getId(),result.get("batchPid"))).isZero();
    }
    @Test void completionAfterAcceptanceButBeforeOutboxCommitSkipsReminderConsumption() {
        String version=publish(),record=recordOf(version);var preview=reminders.preview(record,version,AnnouncementStatistics.Filters.all());
        var accepted=new TransactionTemplate(transactions).execute(state->{
            var result=reminders.execute(AnnouncementReminderService.SUBMIT,record,Map.of("previewToken",preview.get("previewToken")),UniqueIdGenerator.generate());
            var detail=readers.detail(version);var mark=Map.<String,Object>of("snapshotHash",detail.get("snapshotHash"));
            readers.mark(version,mark,false);readers.mark(version,mark,true);return result;
        });
        org.awaitility.Awaitility.await().pollInSameThread().atMost(Duration.ofSeconds(15)).untilAsserted(()->{
            outboxWorker.pollAndDispatch();applyTestMetaContext();
            assertThat(jdbc.queryForMap("SELECT status,error_code FROM ab_announcement_delivery WHERE tenant_id=? AND batch_pid=?",getTestTenant().getId(),accepted.get("batchPid")))
                .containsEntry("status","skipped").containsEntry("error_code","ALREADY_COMPLETE");});
    }
    @Test void reminderPreviewCannotCrossRecordAndChangedOperationCannotReplay() {
        String version=publish(),record=recordOf(version);var preview=reminders.preview(record,version,AnnouncementStatistics.Filters.all());
        String other=recordOf(publish());
        assertThatThrownBy(()->reminders.execute(AnnouncementReminderService.SUBMIT,other,Map.of("previewToken",preview.get("previewToken")),UniqueIdGenerator.generate()))
            .isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
        String operation=UniqueIdGenerator.generate();reminders.execute(AnnouncementReminderService.SUBMIT,record,Map.of("previewToken",preview.get("previewToken")),operation);
        var next=reminders.preview(record,version,AnnouncementStatistics.Filters.all());
        assertThatThrownBy(()->reminders.execute(AnnouncementReminderService.SUBMIT,record,Map.of("previewToken",next.get("previewToken")),operation))
            .isInstanceOf(com.auraboot.framework.exception.ConflictException.class);
    }
    private Map<String,Object> reminderPlan(String version) {
        return jdbc.queryForMap("SELECT p.*,j.actor_user_id,j.actor_member_pid,j.announcement_pid FROM ab_announcement_reminder_plan p JOIN ab_announcement_release j ON j.tenant_id=p.tenant_id AND j.pid=p.release_pid WHERE p.tenant_id=? AND p.version_pid=?",getTestTenant().getId(),version);
    }
    private Instant reminderSlot(String version,int days) {
        var row=jdbc.queryForMap("SELECT c.published_at,v.snapshot->>'timezone' AS timezone FROM ab_announcement_version v JOIN ab_announcement_version_control c ON c.tenant_id=v.tenant_id AND c.version_pid=v.pid WHERE v.tenant_id=? AND v.pid=?",getTestTenant().getId(),version);
        Instant published=((java.sql.Timestamp)row.get("published_at")).toInstant();
        return published.atZone(ZoneId.of((String)row.get("timezone"))).toLocalDate().plusDays(days).atTime(9,0).atZone(ZoneId.of((String)row.get("timezone"))).toInstant();
    }
    @Test void automaticRemindersPersistOneBatchPerDayAndStopAfterThreePositiveBatches() {
        String version=publish();var reference=reminderPlan(version);
        for(int day=2;day<=5;day++) {
            reminderClock.instant=reminderSlot(version,day);reminders.scan(reference);reminders.scan(reference);
            assertThat(reminderPlan(version).get("automatic_batches")).isEqualTo(Math.min(day-1,3));
        }
        assertThat(reminderPlan(version)).containsEntry("status","completed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_announcement_reminder_batch WHERE tenant_id=? AND version_pid=? AND mode='AUTO'",Long.class,getTestTenant().getId(),version)).isEqualTo(3L);
        assertThat(jdbc.queryForList("SELECT intention_count FROM ab_announcement_reminder_batch WHERE tenant_id=? AND version_pid=? AND mode='AUTO'",Integer.class,getTestTenant().getId(),version)).containsExactly(1,1,1);
    }
    @Test void manualAndAutomaticShareTheSamePersistentWindowWithoutChargingAnEmptyDay() {
        String version=publish(),record=recordOf(version);reminderClock.instant=reminderSlot(version,2);
        var preview=reminders.preview(record,version,AnnouncementStatistics.Filters.all());
        reminders.execute(AnnouncementReminderService.SUBMIT,record,Map.of("previewToken",preview.get("previewToken")),UniqueIdGenerator.generate());
        reminders.scan(reminderPlan(version));reminders.scan(reminderPlan(version));
        assertThat(reminderPlan(version).get("automatic_batches")).isEqualTo(0);
        assertThat(jdbc.queryForMap("SELECT status,intention_count,throttled_count FROM ab_announcement_reminder_batch WHERE tenant_id=? AND version_pid=? AND mode='AUTO'",getTestTenant().getId(),version))
            .containsEntry("status","skipped").containsEntry("intention_count",0).containsEntry("throttled_count",1);
        reminderClock.instant=reminderSlot(version,3);reminders.scan(reminderPlan(version));
        assertThat(reminderPlan(version).get("automatic_batches")).isEqualTo(1);
    }
    @Test void automaticDeadlineEqualityStopsButManualReminderRemainsAvailable() {
        String version=publish(),record=recordOf(version);
        reminderClock.instant=jdbc.queryForObject("SELECT ack_due_at FROM ab_announcement_version_control WHERE tenant_id=? AND version_pid=?",java.sql.Timestamp.class,getTestTenant().getId(),version).toInstant();
        reminders.scan(reminderPlan(version));assertThat(reminderPlan(version)).containsEntry("status","completed").containsEntry("automatic_batches",0);
        var preview=reminders.preview(record,version,AnnouncementStatistics.Filters.all());assertThat(preview.get("eligible")).isEqualTo(1L);
        var result=reminders.execute(AnnouncementReminderService.SUBMIT,record,Map.of("previewToken",preview.get("previewToken")),UniqueIdGenerator.generate());assertThat(result.get("actualCount")).isEqualTo(1);
    }
    @Test void concurrentAutomaticScansShareTheRootLockAndDailyUniqueFence() throws Exception {
        String version=publish();var reference=reminderPlan(version);reminderClock.instant=reminderSlot(version,2);
        var ready=new java.util.concurrent.CountDownLatch(2);var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{ready.countDown();if(!start.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Missing concurrent start");reminders.scan(reference);return true;});
            var second=pool.submit(()->{ready.countDown();if(!start.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Missing concurrent start");reminders.scan(reference);return true;});
            assertThat(ready.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();start.countDown();
            assertThat(first.get(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();assertThat(second.get(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
        assertThat(reminderPlan(version).get("automatic_batches")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_announcement_reminder_batch WHERE tenant_id=? AND version_pid=? AND mode='AUTO'",Long.class,getTestTenant().getId(),version)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_announcement_delivery WHERE tenant_id=? AND version_pid=? AND kind='reminder'",Long.class,getTestTenant().getId(),version)).isEqualTo(1L);
    }
    @Test void authorityRevokedAfterAcceptanceBlocksTheBatchBeforeNotificationPersistence() {
        String version=publish(),record=recordOf(version);var preview=reminders.preview(record,version,AnnouncementStatistics.Filters.all());
        try {
            var accepted=new TransactionTemplate(transactions).execute(state->{
                var result=reminders.execute(AnnouncementReminderService.SUBMIT,record,Map.of("previewToken",preview.get("previewToken")),UniqueIdGenerator.generate());
                assertThat(jdbc.update("DELETE FROM ab_role_permission WHERE tenant_id=? AND role_id=? AND permission_id IN (SELECT id FROM ab_permission WHERE tenant_id=? AND code=?)",
                    getTestTenant().getId(),getTestRole().getId(),getTestTenant().getId(),"announcement.announcement.remind")).isEqualTo(1);return result;
            });
            permissions.evictRoleUsers(getTestTenant().getId(),getTestRole().getId());
            org.awaitility.Awaitility.await().pollInSameThread().atMost(Duration.ofSeconds(15)).untilAsserted(()->{
                outboxWorker.pollAndDispatch();applyTestMetaContext();
                assertThat(jdbc.queryForMap("SELECT status,error_code FROM ab_announcement_delivery WHERE tenant_id=? AND batch_pid=?",getTestTenant().getId(),accepted.get("batchPid")))
                    .containsEntry("status","skipped").containsEntry("error_code","ACTOR_OR_VERSION_UNAVAILABLE");});
            assertThat(jdbc.queryForObject("SELECT status FROM ab_announcement_reminder_batch WHERE tenant_id=? AND pid=?",String.class,getTestTenant().getId(),accepted.get("batchPid"))).isEqualTo("blocked");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_notification WHERE tenant_id=? AND user_id=? AND source_type='announcement' AND source_id=?",Long.class,
                getTestTenant().getId(),getTestUser().getId(),version)).isEqualTo(1L);
        } finally {
            grantCommittedPermissionToTestRole("announcement.announcement.remind","announcement","announcement:announcement","remind","Announcement remind");
            permissions.evictRoleUsers(getTestTenant().getId(),getTestRole().getId());
        }
    }
    @Test void blockedOriginalPlanResumesExplicitlyWithoutCatchingUpTheMissedSlot() {
        String version=publish(),record=recordOf(version);reminderClock.instant=reminderSlot(version,2).plusSeconds(600);
        try {
            assertThat(jdbc.update("DELETE FROM ab_role_permission WHERE tenant_id=? AND role_id=? AND permission_id IN (SELECT id FROM ab_permission WHERE tenant_id=? AND code=?)",
                getTestTenant().getId(),getTestRole().getId(),getTestTenant().getId(),"announcement.announcement.remind")).isEqualTo(1);
            permissions.evictRoleUsers(getTestTenant().getId(),getTestRole().getId());
            new AnnouncementReminderWorker(reminderPlans,reminders).tick();applyTestMetaContext();
            assertThat(reminderPlan(version)).containsEntry("status","blocked").containsEntry("automatic_batches",0);
        } finally {
            grantCommittedPermissionToTestRole("announcement.announcement.remind","announcement","announcement:announcement","remind","Announcement remind");
            permissions.evictRoleUsers(getTestTenant().getId(),getTestRole().getId());
        }
        var before=reminders.planStatusByVersion(version);assertThat(before).containsEntry("canResume",true).containsEntry("status","blocked");
        reminders.execute(AnnouncementReminderService.RESUME,record,Map.of("versionPid",version),UniqueIdGenerator.generate());
        reminders.scan(reminderPlan(version));assertThat(reminderPlan(version).get("automatic_batches")).isEqualTo(0);
        reminderClock.instant=reminderSlot(version,3);reminders.scan(reminderPlan(version));
        assertThat(reminderPlan(version)).containsEntry("status","active").containsEntry("automatic_batches",1);
    }
    @Test void httpReminderMetadataIsAllowlistedAndWithdrawnHistoryCannotBeSubmitted() throws Exception {
        String version=publish(),record=recordOf(version);
        var response=http.perform(post("/api/announcements/records/"+record+"/versions/"+version+"/reminders/preview")
            .contentType(MediaType.APPLICATION_JSON).content(codec.encode(AnnouncementStatistics.Filters.all())))
            .andExpect(status().isOk()).andReturn().getResponse();
        String token=json.readTree(response.getContentAsString()).required("data").required("previewToken").textValue();
        var preview=http.perform(get("/api/announcements/reminders/previews/"+token)).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(preview.getContentAsString()).doesNotContain("owner_user_id","owner_member_pid","policy_hash","scope_hash","actorMemberPid");
        assertThat(json.readTree(preview.getContentAsString()).required("data").required("currentEligible").longValue()).isEqualTo(1);
        var accepted=reminders.execute(AnnouncementReminderService.SUBMIT,record,Map.of("previewToken",token),UniqueIdGenerator.generate());
        var batch=http.perform(get("/api/announcements/reminders/batches/"+accepted.get("batchPid"))).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(batch.getContentAsString()).doesNotContain("actor_user_id","actor_member_pid","policy_hash","scope_hash","memberPid");
        assertThat(json.readTree(batch.getContentAsString()).required("data").required("actualCount").longValue()).isEqualTo(1);
        http.perform(get("/api/announcements/versions/"+version+"/reminders/plan")).andExpect(status().isOk());
        int revision=jdbc.queryForObject("SELECT row_version FROM ab_announcement WHERE tenant_id=? AND pid=?",Integer.class,getTestTenant().getId(),record);
        lifecycle.execute("announcement:archive",record,Map.of(),UniqueIdGenerator.generate(),revision);
        assertThat(reminders.planStatusByVersion(version)).containsEntry("status","canceled").containsEntry("canResume",false);
        assertThat(reminders.batchStatus((String)accepted.get("batchPid"))).containsEntry("actualCount",1);
        assertThatThrownBy(()->reminders.execute(AnnouncementReminderService.SUBMIT,record,Map.of("previewToken",token),UniqueIdGenerator.generate()))
            .isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
    }
    @Test void managementPublisherUsesAnAuthorizedHumanNameInsteadOfTheInternalForeignKey() throws Exception {
        String version=publish(),record=recordOf(version);
        var response=http.perform(get("/api/announcements/records/"+record+"/management-summary")).andExpect(status().isOk()).andReturn().getResponse();
        var value=json.readTree(response.getContentAsString()).required("data");
        String expected=getTestUser().getNickName()==null || getTestUser().getNickName().isBlank() ? getTestUser().getUserName() : getTestUser().getNickName();
        assertThat(value.required("publisherName").textValue()).isEqualTo(expected).isNotEqualTo(getTestUser().getId().toString());
        assertThat(value.size()).isEqualTo(2);assertThat(value.required("publishedAt").isTextual()).isTrue();
        assertThat(value.toString()).doesNotContain("published_by","member_pid","tenant_id");
    }
    @Test void reminderHistoryPagesAllAcceptedBatchesAndRechecksCurrentHttpAuthority() throws Exception {
        String version=publish(),record=recordOf(version);
        var preview=reminders.preview(record,version,AnnouncementStatistics.Filters.all());
        String operation=UniqueIdGenerator.generate();var payload=Map.<String,Object>of("previewToken",preview.get("previewToken"));
        var first=reminders.execute(AnnouncementReminderService.SUBMIT,record,payload,operation);
        var empty=reminders.preview(record,version,AnnouncementStatistics.Filters.all());
        for(int index=0;index<25;index++) assertThat(reminders.execute(AnnouncementReminderService.SUBMIT,record,
            Map.of("previewToken",empty.get("previewToken")),UniqueIdGenerator.generate())).containsEntry("actualCount",0).containsEntry("operationState","skipped");
        var page1=reminders.batchHistory(version,1,20);var page2=reminders.batchHistory(version,2,20);
        assertThat(page1).containsEntry("total",26L).containsEntry("pageNum",1).containsEntry("pageSize",20);
        var rows1=(List<Map<String,Object>>)page1.get("records");var rows2=(List<Map<String,Object>>)page2.get("records");
        assertThat(rows1).hasSize(20);assertThat(rows2).hasSize(6);
        var ids=new HashSet<Object>();rows1.forEach(row->assertThat(ids.add(row.get("pid"))).isTrue());rows2.forEach(row->assertThat(ids.add(row.get("pid"))).isTrue());
        assertThat(ids).hasSize(26).contains(first.get("batchPid"));assertThat(rows2.getLast()).containsEntry("pid",first.get("batchPid")).containsEntry("actualCount",1);
        assertThat(reminders.execute(AnnouncementReminderService.SUBMIT,record,payload,operation)).isEqualTo(first);
        var response=http.perform(get("/api/announcements/versions/"+version+"/reminders/batches").param("pageNum","2").param("pageSize","20"))
            .andExpect(status().isOk()).andReturn().getResponse();
        var data=json.readTree(response.getContentAsString()).required("data");assertThat(data.required("total").longValue()).isEqualTo(26);
        assertThat(data.required("records")).hasSize(6);assertThat(data.toString()).doesNotContain("actor_user_id","actor_member_pid","scope_hash","policy_hash","memberPid");
        try {
            assertThat(jdbc.update("DELETE FROM ab_role_permission WHERE tenant_id=? AND role_id=? AND permission_id IN (SELECT id FROM ab_permission WHERE tenant_id=? AND code=?)",
                getTestTenant().getId(),getTestRole().getId(),getTestTenant().getId(),"announcement.announcement.remind")).isEqualTo(1);
            permissions.evictRoleUsers(getTestTenant().getId(),getTestRole().getId());
            http.perform(get("/api/announcements/versions/"+version+"/reminders/batches")).andExpect(status().isForbidden());
        } finally {
            grantCommittedPermissionToTestRole("announcement.announcement.remind","announcement","announcement:announcement","remind","Announcement remind");
            permissions.evictRoleUsers(getTestTenant().getId(),getTestRole().getId());
        }
    }
    @Test void staleAutomaticFailureCannotOverwriteAnExplicitlyResumedPlan() {
        String version=publish(),record=recordOf(version);var stale=reminderPlan(version);
        assertThat(reminders.block(stale,"ORIGINAL_FAILURE")).isTrue();
        reminderClock.instant=reminderSlot(version,2).plusSeconds(600);
        reminders.execute(AnnouncementReminderService.RESUME,record,Map.of("versionPid",version),UniqueIdGenerator.generate());
        assertThat(reminders.block(stale,"LATE_FAILURE")).isFalse();
        assertThat(reminderPlan(version)).containsEntry("status","active").containsEntry("automatic_batches",0).containsEntry("error_code",null);
        assertThat(reminders.block(reminderPlan(version),"CURRENT_FAILURE")).isTrue();
        assertThat(reminderPlan(version)).containsEntry("status","blocked").containsEntry("error_code","CURRENT_FAILURE");
    }
    @Test void withdrawalCancelsPlansAndLatestPlanReadIgnoresAnUnpublishedNewVersion() throws Exception {
        String version=publish(),record=recordOf(version);var stale=reminderPlan(version);
        assertThat(reminders.latestPlanStatus(record)).containsEntry("versionPid",version);
        int revision=jdbc.queryForObject("SELECT row_version FROM ab_announcement WHERE tenant_id=? AND pid=?",Integer.class,getTestTenant().getId(),record);
        var withdrawn=lifecycle.execute("announcement:archive",record,Map.of(),UniqueIdGenerator.generate(),revision);
        assertThat(reminderPlan(version)).containsEntry("status","canceled");assertThat(reminders.block(stale,"LATE_FAILURE")).isFalse();
        var draft=lifecycle.execute("announcement:republish",record,Map.of(),UniqueIdGenerator.generate(),((Number)withdrawn.get("row_version")).intValue());
        var accepted=releases.execute("announcement:publish",record,Map.of(),UniqueIdGenerator.generate(),((Number)draft.get("row_version")).intValue());
        assertThat(accepted.get("versionPid")).isNotEqualTo(version);
        var status=http.perform(get("/api/announcements/records/"+record+"/reminders/plan")).andExpect(status().isOk()).andReturn().getResponse();
        var result=json.readTree(status.getContentAsString()).required("data");assertThat(result.required("versionPid").textValue()).isEqualTo(version);
        assertThat(result.required("status").textValue()).isEqualTo("canceled");
        releases.execute("announcement:cancel_schedule",record,Map.of(),UniqueIdGenerator.generate(),((Number)accepted.get("row_version")).intValue());
    }
    @Test void httpSummaryAndDetailsRemainAtTheSameInstantAfterNaturalReadAndAcknowledgementChanges() throws Exception {
        String version=publish();
        var response=http.perform(post("/api/announcements/versions/"+version+"/statistics/snapshots")
            .contentType(MediaType.APPLICATION_JSON).content(codec.encode(AnnouncementStatistics.Filters.all()))).andExpect(status().isOk()).andReturn();
        var captured=json.readTree(response.getResponse().getContentAsString()).path("data");
        String token=captured.path("snapshotToken").asText();assertThat(token).isNotBlank();
        assertThat(captured.path("summary").path("actualRecipients").asLong()).isEqualTo(1);
        assertThat(captured.path("summary").path("read").asLong()).isZero();
        var detail=readers.detail(version);String hash=(String)detail.get("snapshotHash");
        readers.mark(version,Map.of("snapshotHash",hash),false);readers.mark(version,Map.of("snapshotHash",hash),true);
        var old=http.perform(get("/api/announcements/statistics/snapshots/"+token+"/recipients")).andExpect(status().isOk()).andReturn();
        var page=json.readTree(old.getResponse().getContentAsString()).path("data");
        assertThat(page.path("asOf").asText()).isEqualTo(captured.path("asOf").asText());
        assertThat(page.path("records").get(0).path("readState").asText()).isEqualTo("unread");
        assertThat(page.path("records").toString()).doesNotContain("memberPid","owner_user_id","organization","displayedDepartmentPids","matchesFilters");
        assertThat(codec.encode(statistics.summary(token).get("summary"))).isEqualTo(codec.encode(codec.object(captured.path("summary").toString())));
        var fresh=statistics.capture(version,AnnouncementStatistics.Filters.all());
        assertThat(codec.object(codec.encode(fresh.get("summary")))).containsEntry("read",1).containsEntry("acknowledged",1);
        assertThat(fresh.get("snapshotToken")).isNotEqualTo(token);
        assertThat(statistics.authorizeExport(token,true).get("version_pid")).isEqualTo(version);
        var tx=new TransactionTemplate(transactions);
        assertThatThrownBy(()->tx.executeWithoutResult(state->{state.setRollbackOnly();
            jdbc.update("UPDATE ab_announcement_statistics_snapshot SET summary='{}'::jsonb WHERE tenant_id=? AND pid=?",getTestTenant().getId(),token);
        })).hasMessageContaining("Announcement statistics snapshot is immutable");
        assertThatThrownBy(()->tx.executeWithoutResult(state->{state.setRollbackOnly();
            jdbc.update("INSERT INTO ab_announcement_statistics_row(tenant_id,snapshot_pid,user_pid,member_pid,organization_snapshot,display_proof,view_payload,matches_filters) VALUES(?,?,?,?, '{}'::jsonb,'{}'::jsonb,'{}'::jsonb,TRUE)",
                getTestTenant().getId(),token,UniqueIdGenerator.generate(),getTestTenantMember().getPid());
        })).hasMessageContaining("Announcement statistics snapshot is missing or sealed");
    }
    @Test void revokingCurrentStatisticsPermissionDeniesAnExistingSnapshotWithoutErasingIt() throws Exception {
        String version=publish();String token=(String)statistics.capture(version,AnnouncementStatistics.Filters.all()).get("snapshotToken");
        try {
            assertThat(jdbc.update("DELETE FROM ab_role_permission WHERE tenant_id=? AND role_id=? AND permission_id IN (SELECT id FROM ab_permission WHERE tenant_id=? AND code=?)",
                getTestTenant().getId(),getTestRole().getId(),getTestTenant().getId(),"announcement.announcement.stats")).isEqualTo(1);
            permissions.evictRoleUsers(getTestTenant().getId(),getTestRole().getId());
            assertThat(permissions.hasPermission(getTestUser().getId(),"announcement.announcement.stats")).isFalse();
            http.perform(get("/api/announcements/statistics/snapshots/"+token)).andExpect(status().isForbidden());
            assertThatThrownBy(()->statistics.authorizeExport(token,false)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        } finally {
            grantCommittedPermissionToTestRole("announcement.announcement.stats","announcement","announcement:announcement","stats","Announcement stats");
            permissions.evictRoleUsers(getTestTenant().getId(),getTestRole().getId());
        }
        assertThat(statistics.summary(token).get("snapshotToken")).isEqualTo(token);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_announcement_statistics_row WHERE tenant_id=? AND snapshot_pid=?",Integer.class,getTestTenant().getId(),token)).isEqualTo(1);
    }
    @Test void replacementMembershipWithTheSameEffectiveGrantsCannotInheritTheOldSnapshot() {
        String version=publish();String token=(String)statistics.capture(version,AnnouncementStatistics.Filters.all()).get("snapshotToken");
        try {
            assertThatThrownBy(()->new TransactionTemplate(transactions).executeWithoutResult(state->{
                state.setRollbackOnly();
                jdbc.update("UPDATE ab_tenant_member SET deleted_flag=TRUE,status='inactive' WHERE tenant_id=? AND id=?",getTestTenant().getId(),getTestTenantMember().getId());
                var replacement=memberships.addMember(getTestUser().getId(),getTestTenant().getId(),"active");
                assertThat(replacement.getPid()).isNotEqualTo(getTestTenantMember().getPid());
                assertThat(roles.assignRolesToMember(replacement.getId(),List.of(getTestRole().getId()),getTestTenant().getId(),null)).isTrue();
                MetaContext.setMemberId(replacement.getId());
                permissions.evictUserPermissions(getTestTenant().getId(),getTestUser().getId());
                assertThat(permissions.hasPermission(getTestUser().getId(),"announcement.announcement.stats")).isTrue();
                assertThat(permissions.hasPermission(getTestUser().getId(),"announcement.announcement.export")).isTrue();
                statistics.authorizeExport(token,false);
            })).isInstanceOf(org.springframework.security.access.AccessDeniedException.class).hasMessageContaining("permissions changed");
        } finally {applyTestMetaContext();permissions.evictUserPermissions(getTestTenant().getId(),getTestUser().getId());}
        assertThat(statistics.summary(token).get("snapshotToken")).isEqualTo(token);
    }
    @Test void expiredViewCannotCreateAnExportButAcceptedExportAuthorizationKeepsItsRetentionWindow() {
        String version=publish();String original=(String)statistics.capture(version,AnnouncementStatistics.Filters.all()).get("snapshotToken");
        Long tenant=getTestTenant().getId();var source=mapper.snapshot(tenant,original);var fact=mapper.authorizationRows(tenant,original).getFirst();
        String token=UniqueIdGenerator.generate();
        new TransactionTemplate(transactions).executeWithoutResult(state->{
            assertThat(mapper.insertSnapshot(tenant,token,version,getTestUser().getId(),getTestTenantMember().getPid(),Instant.now().minus(Duration.ofMinutes(35)),
                source.get("metadata").toString(),source.get("summary").toString(),source.get("filters").toString(),1,1)).isEqualTo(1);
            assertThat(mapper.insertRows(tenant,token,List.of(new AnnouncementStatisticsMapper.StoredRow((String)fact.get("user_pid"),(String)fact.get("member_pid"),
                fact.get("organization_snapshot").toString(),fact.get("display_proof").toString(),mapper.page(tenant,original,1,0).getFirst(),true)))).isEqualTo(1);
            assertThat(mapper.seal(tenant,token)).isEqualTo(1);
        });
        assertThatThrownBy(()->statistics.summary(token)).isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
        assertThatThrownBy(()->statistics.authorizeExport(token,true)).isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
        assertThat(statistics.authorizeExport(token,false).get("version_pid")).isEqualTo(version);
    }

    private void importExportTranslations() throws Exception {
        var file=java.nio.file.Path.of("../plugins/core-announcement/config/i18n.json");
        if(!java.nio.file.Files.exists(file)) file=java.nio.file.Path.of("../../plugins/core-announcement/config/i18n.json");
        var resources=new ArrayList<com.auraboot.framework.i18n.entity.I18nResource>();
        for(var entry:json.readTree(java.nio.file.Files.readString(file))) {
            if("announcement.statistics.version_option".equals(entry.path("key").asText())) {
                for(String locale:List.of("zh-CN","en-US")) resources.add(com.auraboot.framework.i18n.entity.I18nResource.builder()
                    .tenantId(getTestTenant().getId()).i18nKey(entry.required("key").textValue()).lang(locale)
                    .value(entry.required(locale).textValue()).source("import").status("approved").build());
                continue;
            }
            if(!entry.path("key").asText().startsWith("announcement.statistics.csv.")) continue;
            resources.add(com.auraboot.framework.i18n.entity.I18nResource.builder()
                .tenantId(getTestTenant().getId()).i18nKey(entry.required("key").textValue()).lang("en-US")
                .value(entry.required("en-US").textValue()).source("import").status("approved").build());
        }
        assertThat(resources).hasSize(36);
        assertThat(translationResources.batchUpsert(resources)).isEqualTo(36);messages.clearCache("en-US");messages.clearCache("zh-CN");
    }
    @Test void actualExportUsesOriginalActorAndCapturedRowsDespiteLaterReadAndTranslationChanges() throws Exception {
        importExportTranslations();org.springframework.context.i18n.LocaleContextHolder.setLocale(Locale.US);
        java.nio.file.Path output=java.nio.file.Files.createTempFile("announcement-real-export-",".csv");
        try {
            String version=publish();String token=(String)statistics.capture(version,AnnouncementStatistics.Filters.all()).get("snapshotToken");
            var prepared=exports.prepare(token);
            assertThat(prepared.rowCount()).isEqualTo(1);
            var owner=new com.auraboot.framework.meta.service.DomainExportSource.Owner(getTestTenant().getId(),getTestUser().getId(),getTestTenantMember().getPid());
            var detail=readers.detail(version);var mark=Map.<String,Object>of("snapshotHash",detail.get("snapshotHash"));
            readers.mark(version,mark,false);readers.mark(version,mark,true);
            var translation=translationResources.findByKeyAndLang("announcement.statistics.csv.state.unread","en-US");
            String original=translation.getValue();
            try {
                translation.setValue("Changed after acceptance");translationResources.upsert(translation);messages.clearCache("en-US");
                MetaContext.clear();
                var progress=new ArrayList<Integer>();
                var result=exports.generate(prepared,owner,output,(percent,message)->progress.add(percent));
                assertThat(MetaContext.exists()).isFalse();
                assertThat(result.getSuccess()).isTrue();assertThat(result.getRecordCount()).isEqualTo(1);
                assertThat(result.getFileSize()).isEqualTo(java.nio.file.Files.size(output));
                assertThat(progress).containsExactly(90);
                String csv=java.nio.file.Files.readString(output);
                assertThat(csv).startsWith("\uFEFFAnnouncement title,Version,Published at,Recipient,Departments at publication,Matched roles,")
                    .contains(",Unread,Not yet,Yes,").contains(",Pending acknowledgement,Not yet,No,Active,")
                    .contains("[Asia/Shanghai]").doesNotContain("Changed after acceptance",token,getTestTenantMember().getPid(),version);
                Instant deadline=Instant.parse(prepared.evidence().required("snapshot").required("metadata").required("acknowledgementDeadline").textValue());
                assertThat(csv).contains(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(deadline.atZone(ZoneId.of("Asia/Shanghai")))+" [Asia/Shanghai]");
                assertThat(java.nio.file.Files.readAllBytes(output)).startsWith((byte)0xef,(byte)0xbb,(byte)0xbf);
            } finally {applyTestMetaContext();translation.setValue(original);translationResources.upsert(translation);messages.clearCache("en-US");}
        } finally {java.nio.file.Files.deleteIfExists(output);org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();applyTestMetaContext();}
    }
    @Test void acceptedExportRechecksCurrentOriginalActorPermissionsBeforeWritingAnyCsvBytes() throws Exception {
        importExportTranslations();org.springframework.context.i18n.LocaleContextHolder.setLocale(Locale.US);
        java.nio.file.Path output=java.nio.file.Files.createTempFile("announcement-denied-export-",".csv");
        try {
            String version=publish();String token=(String)statistics.capture(version,AnnouncementStatistics.Filters.all()).get("snapshotToken");
            var prepared=exports.prepare(token);
            var owner=new com.auraboot.framework.meta.service.DomainExportSource.Owner(getTestTenant().getId(),getTestUser().getId(),getTestTenantMember().getPid());
            assertThat(jdbc.update("DELETE FROM ab_role_permission WHERE tenant_id=? AND role_id=? AND permission_id IN (SELECT id FROM ab_permission WHERE tenant_id=? AND code=?)",
                getTestTenant().getId(),getTestRole().getId(),getTestTenant().getId(),"announcement.announcement.export")).isEqualTo(1);
            permissions.evictRoleUsers(getTestTenant().getId(),getTestRole().getId());
            assertThat(permissions.hasPermission(getTestUser().getId(),"announcement.announcement.export")).isFalse();
            MetaContext.clear();
            assertThatThrownBy(()->exports.generate(prepared,owner,output,(percent,message)->{}))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            assertThat(java.nio.file.Files.size(output)).isZero();assertThat(MetaContext.exists()).isFalse();
        } finally {
            applyTestMetaContext();grantCommittedPermissionToTestRole("announcement.announcement.export","announcement","announcement:announcement","export","Announcement export");
            permissions.evictRoleUsers(getTestTenant().getId(),getTestRole().getId());java.nio.file.Files.deleteIfExists(output);
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
        }
    }
    @Test void canceledExportQueueCannotBeDeletedWhileItsOwnerStatusIsStillAvailable() throws Exception {
        importExportTranslations();
        String version=publish();String token=(String)statistics.capture(version,AnnouncementStatistics.Filters.all()).get("snapshotToken");
        String pid=new TransactionTemplate(transactions).execute(state->{
            var submitted=exportTasks.submit(AnnouncementStatisticsExportSource.CODE,token);
            var row=exportRegistry.findByPid(submitted.getPid());
            String code=row.getRequestParams().required("asyncTaskCode").textValue();
            assertThat(backgroundTasks.cancelTask(code).getStatus()).isEqualTo("cancelled");
            return submitted.getPid();
        });
        var row=exportRegistry.findByPid(pid);String code=row.getRequestParams().required("asyncTaskCode").textValue();
        var denied=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/async-tasks/"+code))
                .andExpect(status().isUnprocessableEntity()).andReturn().getResponse();
        assertThat(denied.getContentAsString()).contains("export.domain_task_retained");
        assertThat(exportTasks.status(pid).getStatus()).isEqualTo("cancelled");
        assertThat(exportTasks.status(pid).getDownloadUrl()).isNull();
        assertThat(backgroundTasks.getTask(code).getStatus()).isEqualTo("cancelled");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_domain_export_attempt WHERE tenant_id=? AND export_task_pid=?",Long.class,
                getTestTenant().getId(),pid)).isZero();
    }

    @Test void durableExportCompletesWithPrivateArtifactAndEveryReadSurfaceRechecksCurrentSourceAuthority() throws Exception {
        String privateRoot=System.getenv("AURA_FILE_STORAGE_PATH");
        assertThat(privateRoot).as("The managed test invocation must specify its task-owned private artifact directory").isNotBlank();
        assertThat(storageProperties.getLocal().getBasePath()).isEqualTo(privateRoot);
        importExportTranslations();org.springframework.context.i18n.LocaleContextHolder.setLocale(Locale.US);
        String pid=null;
        try {
            String version=publish();String token=(String)statistics.capture(version,AnnouncementStatistics.Filters.all()).get("snapshotToken");
            var submitted=new TransactionTemplate(transactions).execute(state->exportTasks.submit(AnnouncementStatisticsExportSource.CODE,token));
            assertThat(submitted.getPid()).isNotBlank();pid=submitted.getPid();String taskPid=pid;
            org.awaitility.Awaitility.await().pollInSameThread().atMost(Duration.ofSeconds(15)).untilAsserted(()->
                assertThat(exportTasks.status(taskPid).getStatus()).isEqualTo("completed"));
            var stored=exportRegistry.findByPid(pid);
            assertThat(stored.getRequestParams().isObject()).isTrue();
            assertThat(stored.getRequestParams().path("sourceType").textValue()).isEqualTo("domain");
            assertThat(stored.getRequestParams().path("checksumSha256").textValue()).matches("[0-9a-f]{64}");
            assertThat(stored.getFileKey()).startsWith("exports/").endsWith(".csv");
            var statusResponse=http.perform(get("/api/exports/domain/"+pid)).andExpect(status().isOk()).andReturn().getResponse();
            assertThat(statusResponse.getContentAsString()).contains("/api/exports/domain/"+pid+"/download")
                .doesNotContain(stored.getFileKey(),"temp_path","memberPid","executionToken","createdBy","tenantId");
            var publicSource=json.readTree(statusResponse.getContentAsString()).required("data").required("sourceSnapshot");
            assertThat(json.readTree(statusResponse.getContentAsString()).required("data").required("title").textValue())
                .isEqualTo(publicSource.required("title").textValue());
            assertThat(publicSource.required("versionPid").textValue()).isEqualTo(version);
            assertThat(publicSource.required("asOf").textValue()).isEqualTo(stored.getRequestParams().required("prepared").required("evidence")
                .required("snapshot").required("asOf").textValue());
            assertThat(publicSource.required("rowCount").longValue()).isEqualTo(1);
            assertThat(publicSource.toString()).doesNotContain("digest","labels","owner","memberPid","organization");
            var first=http.perform(get("/api/exports/domain/"+pid+"/download")).andExpect(status().isOk()).andReturn().getResponse();
            assertThat(first.getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(first.getHeader("Content-Disposition")).contains("announcement-statistics.csv");
            byte[] bytes=first.getContentAsByteArray();assertThat(bytes).startsWith((byte)0xef,(byte)0xbb,(byte)0xbf);
            assertThat(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).contains(",Unread,Not yet,Yes,");
            var detail=readers.detail(version);var mark=Map.<String,Object>of("snapshotHash",detail.get("snapshotHash"));
            readers.mark(version,mark,false);readers.mark(version,mark,true);
            var second=http.perform(get("/api/exports/domain/"+pid+"/download")).andExpect(status().isOk()).andReturn().getResponse();
            assertThat(second.getContentAsByteArray()).isEqualTo(bytes);
            assertThat(legacyExports.getTaskStatus(pid).getStatus()).isEqualTo("completed");
            assertThat(executionJobs.recent(100)).anySatisfy(job->{assertThat(job.getPid()).isEqualTo(taskPid);
                assertThat(job.getDownloadUrl()).isEqualTo("/api/exports/domain/"+taskPid+"/download");});
            assertThat(jdbc.update("DELETE FROM ab_role_permission WHERE tenant_id=? AND role_id=? AND permission_id IN (SELECT id FROM ab_permission WHERE tenant_id=? AND code=?)",
                getTestTenant().getId(),getTestRole().getId(),getTestTenant().getId(),"announcement.announcement.export")).isEqualTo(1);
            permissions.evictRoleUsers(getTestTenant().getId(),getTestRole().getId());
            assertThat(permissions.hasPermission(getTestUser().getId(),"announcement.announcement.export")).isFalse();
            http.perform(get("/api/exports/domain/"+pid)).andExpect(status().isForbidden());
            var denied=http.perform(get("/api/exports/domain/"+pid+"/download")).andExpect(status().isForbidden()).andReturn().getResponse();
            assertThat(new String(denied.getContentAsByteArray(),java.nio.charset.StandardCharsets.UTF_8)).doesNotStartWith("\uFEFF");
            assertThatThrownBy(()->legacyExports.getTaskStatus(taskPid)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            assertThat(legacyExports.getRecentTasks("domain:"+AnnouncementStatisticsExportSource.CODE,100)).isEmpty();
            assertThat(executionJobs.recent(100)).noneMatch(job->taskPid.equals(job.getPid()));
        } finally {
            grantCommittedPermissionToTestRole("announcement.announcement.export","announcement","announcement:announcement","export","Announcement export");
            permissions.evictRoleUsers(getTestTenant().getId(),getTestRole().getId());org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
        }
    }
    @Test void businessExportAcceptanceReplaysOneTaskRejectsChangedCaptureAndReauthorizesBeforeReplay() throws Exception {
        importExportTranslations();org.springframework.context.i18n.LocaleContextHolder.setLocale(Locale.US);
        try {
            String version=publish();var captured=statistics.capture(version,AnnouncementStatistics.Filters.all());
            String token=(String)captured.get("snapshotToken"),record=(String)codec.object(codec.encode(captured.get("metadata"))).get("recordPid");
            int revision=jdbc.queryForObject("SELECT row_version FROM ab_announcement WHERE tenant_id=? AND pid=?",Integer.class,getTestTenant().getId(),record);
            String operation=UniqueIdGenerator.generate();var payload=Map.<String,Object>of("snapshotToken",token);
            var first=exportCommand.execute(record,payload,operation);
            assertThat(exportCommand.execute(record,payload,operation)).isEqualTo(first);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_export_task WHERE tenant_id=? AND created_by=? AND query_code=? AND request_params->'prepared'->>'reference'=?",
                Integer.class,getTestTenant().getId(),getTestUser().getId(),"domain:"+AnnouncementStatisticsExportSource.CODE,token)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_announcement_audit WHERE tenant_id=? AND command_code=? AND operation_id=?",
                Integer.class,getTestTenant().getId(),AnnouncementStatisticsExportCommand.CODE,operation)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT row_version FROM ab_announcement WHERE tenant_id=? AND pid=?",Integer.class,getTestTenant().getId(),record)).isEqualTo(revision);
            String replacement=(String)statistics.capture(version,AnnouncementStatistics.Filters.all()).get("snapshotToken");
            assertThatThrownBy(()->exportCommand.execute(record,Map.of("snapshotToken",replacement),operation))
                .isInstanceOf(com.auraboot.framework.exception.ConflictException.class);
            String otherVersion=publish(),other=(String)statistics.capture(otherVersion,AnnouncementStatistics.Filters.all()).get("snapshotToken");
            assertThatThrownBy(()->exportCommand.execute(record,Map.of("snapshotToken",other),UniqueIdGenerator.generate()))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class).hasMessageContaining("different record");
            org.awaitility.Awaitility.await().pollInSameThread().atMost(Duration.ofSeconds(15)).untilAsserted(()->
                assertThat(exportTasks.status((String)first.get("exportTaskPid")).getStatus()).isEqualTo("completed"));
            assertThat(jdbc.update("DELETE FROM ab_role_permission WHERE tenant_id=? AND role_id=? AND permission_id IN (SELECT id FROM ab_permission WHERE tenant_id=? AND code=?)",
                getTestTenant().getId(),getTestRole().getId(),getTestTenant().getId(),"announcement.announcement.export")).isEqualTo(1);
            permissions.evictRoleUsers(getTestTenant().getId(),getTestRole().getId());
            assertThat(permissions.hasPermission(getTestUser().getId(),"announcement.announcement.export")).isFalse();
            assertThatThrownBy(()->exportCommand.execute(record,payload,operation)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        } finally {
            grantCommittedPermissionToTestRole("announcement.announcement.export","announcement","announcement:announcement","export","Announcement export");
            permissions.evictRoleUsers(getTestTenant().getId(),getTestRole().getId());org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
        }
    }
    @Test void recordCaptureAndFrozenDepartmentOptionsSupportActiveAndHistoricalVersionsWithoutFollowingDirectoryRenames() throws Exception {
        importExportTranslations();Long tenant=getTestTenant().getId();
        String department=UniqueIdGenerator.generate(),position=UniqueIdGenerator.generate(),employee=UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO mt_org_department(pid,tenant_id,org_dept_name,org_dept_status) VALUES(?,?,'Statistics frozen department','active')",department,tenant);
        jdbc.update("INSERT INTO mt_org_position(pid,tenant_id,org_pos_name,org_pos_dept_id,org_pos_level) VALUES(?,?,'Staff',?,'1')",position,tenant,department);
        jdbc.update("INSERT INTO mt_org_employee(pid,tenant_id,org_emp_name,org_emp_dept_id,org_emp_position_id,org_emp_member_id,org_emp_status) VALUES(?,?,'Statistics employee',?,?,?,'active')",
            employee,tenant,department,position,getTestTenantMember().getPid());
        String firstVersion=publish();var firstCapture=statistics.capture(firstVersion,AnnouncementStatistics.Filters.all());
        String record=json.valueToTree(firstCapture).required("metadata").required("recordPid").textValue();
        var response=http.perform(post("/api/announcements/records/"+record+"/statistics/snapshots")
            .contentType(MediaType.APPLICATION_JSON).content(codec.encode(AnnouncementStatistics.Filters.all())))
            .andExpect(status().isOk()).andReturn().getResponse();
        var captured=json.readTree(response.getContentAsString()).required("data");String token=captured.required("snapshotToken").textValue();
        assertThat(captured.required("pid").textValue()).isEqualTo(token);
        assertThat(captured.required("title").textValue()).isEqualTo(captured.required("metadata").required("title").textValue());
        assertThat(captured.required("versionPid").textValue()).isEqualTo(firstVersion);
        jdbc.update("UPDATE mt_org_department SET org_dept_name='Statistics renamed department' WHERE tenant_id=? AND pid=?",tenant,department);
        var options=http.perform(get("/api/announcements/statistics/snapshots/"+token+"/departments")).andExpect(status().isOk()).andReturn().getResponse();
        var departments=json.readTree(options.getContentAsString()).required("data");
        assertThat(departments.findValuesAsText("value")).contains(department);
        var capturedDepartment=java.util.stream.StreamSupport.stream(departments.spliterator(),false)
            .filter(option->department.equals(option.required("value").textValue())).findFirst().orElseThrow();
        assertThat(capturedDepartment.required("label").required("en-US").textValue()).isEqualTo("Statistics frozen department");
        assertThat(capturedDepartment.required("label").required("zh-CN").textValue()).isEqualTo("Statistics frozen department");
        int revision=jdbc.queryForObject("SELECT row_version FROM ab_announcement WHERE tenant_id=? AND pid=?",Integer.class,tenant,record);
        var withdrawn=lifecycle.execute("announcement:archive",record,Map.of(),UniqueIdGenerator.generate(),revision);
        assertThat(statistics.captureRecord(record,AnnouncementStatistics.Filters.all()).get("versionPid")).isEqualTo(firstVersion);
        var revised=lifecycle.execute("announcement:republish",record,Map.of(),UniqueIdGenerator.generate(),((Number)withdrawn.get("row_version")).intValue());
        var accepted=releases.execute("announcement:publish",record,Map.of(),UniqueIdGenerator.generate(),((Number)revised.get("row_version")).intValue());
        MetaContext.clear();new AnnouncementReleaseWorker(releases).tick();applyTestMetaContext();
        String secondVersion=(String)accepted.get("versionPid");assertThat(secondVersion).isNotEqualTo(firstVersion);
        var latest=statistics.captureRecord(record,AnnouncementStatistics.Filters.all());assertThat(latest.get("versionPid")).isEqualTo(secondVersion);
        var versions=http.perform(get("/api/announcements/records/"+record+"/statistics/versions")).andExpect(status().isOk()).andReturn().getResponse();
        var choices=json.readTree(versions.getContentAsString()).required("data");
        assertThat(choices).hasSize(2);assertThat(choices.get(0).required("value").textValue()).isEqualTo(secondVersion);
        assertThat(choices.get(0).required("label").required("en-US").textValue()).isEqualTo("Version 2");
        var historical=statistics.capture(firstVersion,AnnouncementStatistics.Filters.all());
        assertThat(statistics.departments((String)historical.get("snapshotToken"))).anySatisfy(option->{
            assertThat(option.value()).isEqualTo(department);assertThat(option.label().get("en-US")).isEqualTo("Statistics frozen department");
        });
        assertThat(statistics.departments((String)latest.get("snapshotToken"))).anySatisfy(option->{
            assertThat(option.value()).isEqualTo(department);assertThat(option.label().get("en-US")).isEqualTo("Statistics renamed department");
        });
    }
}
