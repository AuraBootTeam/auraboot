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
    @Autowired AnnouncementStatisticsMapper mapper;
    @Autowired AnnouncementCodec codec;
    @Autowired UserPermissionService permissions;
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
    @Test void revokingCurrentStatisticsPermissionDeniesAnExistingSnapshotWithoutErasingIt() {
        String version=publish();String token=(String)statistics.capture(version,AnnouncementStatistics.Filters.all()).get("snapshotToken");
        try {
            assertThatThrownBy(()->new TransactionTemplate(transactions).executeWithoutResult(state->{
                state.setRollbackOnly();
                jdbc.update("DELETE FROM ab_role_permission WHERE tenant_id=? AND role_id=? AND permission_id IN (SELECT id FROM ab_permission WHERE tenant_id=? AND code=?)",
                    getTestTenant().getId(),getTestRole().getId(),getTestTenant().getId(),"announcement.announcement.stats");
                permissions.evictUserPermissions(getTestTenant().getId(),getTestUser().getId());
                statistics.authorizeExport(token,false);
            })).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        } finally {permissions.evictUserPermissions(getTestTenant().getId(),getTestUser().getId());}
        assertThat(statistics.summary(token).get("snapshotToken")).isEqualTo(token);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_announcement_statistics_row WHERE tenant_id=? AND snapshot_pid=?",Integer.class,getTestTenant().getId(),token)).isEqualTo(1);
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
}
