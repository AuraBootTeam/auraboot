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

/** Actual authorized HTTP capture, immutable JSONB persistence and current-permission reauthorization. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
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
        for(String action:List.of("edit","publish","publish_all","exempt","stats","export"))
            grantCommittedPermissionToTestRole("announcement.announcement."+action,"announcement","announcement:announcement",action,"Announcement "+action);
        applyTestMetaContext();
        var authorities=AuthorityUtils.createAuthorityList("role_admin");
        var user=new CustomUserDetails(getTestUser().getUserName(),"test-password",getTestUser().getId(),getTestUser().getPid(),authorities,true,true,true,true);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,authorities));
        http=MockMvcBuilders.webAppContextSetup(context).build();
    }
    @AfterEach void clearAuthentication(){SecurityContextHolder.clearContext();}
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
            if(!entry.path("key").asText().startsWith("announcement.statistics.csv.")) continue;
            resources.add(com.auraboot.framework.i18n.entity.I18nResource.builder()
                .tenantId(getTestTenant().getId()).i18nKey(entry.required("key").textValue()).lang("en-US")
                .value(entry.required("en-US").textValue()).source("import").status("approved").build());
        }
        assertThat(resources).hasSize(34);
        assertThat(translationResources.batchUpsert(resources)).isEqualTo(34);messages.clearCache("en-US");
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
}
