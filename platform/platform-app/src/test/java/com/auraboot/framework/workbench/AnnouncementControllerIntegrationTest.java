package com.auraboot.framework.workbench;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.auraboot.framework.workbench.entity.Announcement;
import com.auraboot.framework.workbench.mapper.AnnouncementMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = TestApplication.class, properties={"aura.notification.plugin-delivery.enabled=true","auraboot.announcement.worker-enabled=false"})
@ActiveProfiles("integration-test")
@DisplayName("AnnouncementController - Integration Tests")
@Transactional
class AnnouncementControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AnnouncementMapper announcementMapper;

    @Autowired
    private PermissionMapper permissionMapper;

    @Autowired
    private RolePermissionMapper rolePermissionMapper;

    @Autowired
    private UserPermissionService userPermissionService;

    private MockMvc mockMvc;

    @BeforeEach
    void setupMockMvc() {
        grantAnnouncementManageToTestRole();
        Filter metaContextFilter = (request, response, chain) -> {
            try {
                MetaContext.setContext(
                        getTestTenant().getId(),
                        getTestUser().getId(),
                        getTestUser().getPid(),
                        getTestUser().getUserName()
                );
                MetaContext.setMemberId(getTestTenantMember().getId());
                CustomUserDetails userDetails = new CustomUserDetails(
                        getTestUser().getUserName(), "test-password",
                        getTestUser().getId(), getTestUser().getPid(),
                        AuthorityUtils.createAuthorityList("role_admin"),
                        true, true, true, true);
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));
                chain.doFilter(request, response);
            } finally {
                MetaContext.clear();
                SecurityContextHolder.clearContext();
            }
        };

        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .addFilter(metaContextFilter, "/*")
                .build();
    }

    private void grantAnnouncementManageToTestRole() {
        for(String action : List.of("edit","delete","withdraw","publish","publish_all","exempt")) {
            grantCommittedPermissionToTestRole("announcement.announcement."+action,
                "announcement","announcement:announcement",action,"Announcement "+action);
        }
    }


    @Autowired private com.auraboot.framework.workbench.service.AnnouncementLifecycleService lifecycle;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private void identity() {
        MetaContext.setContext(getTestTenant().getId(),getTestUser().getId(),
            getTestUser().getPid(),getTestUser().getUserName());
        MetaContext.setMemberId(getTestTenantMember().getId());
    }

    @Test
    void controlledDraftReplayConflictAndDeletePersistAtomically() throws Exception {
        identity();
        String operation=UniqueIdGenerator.generate();
        Map<String,Object> input=Map.of("title","Draft "+operation,"pinned",true);
        Map<String,Object> created=lifecycle.execute("announcement:create_announcement",null,input,operation,null);
        String pid=(String)created.get("pid");
        assertEquals(created,lifecycle.execute("announcement:create_announcement",null,input,operation,null));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM ab_announcement_audit WHERE tenant_id=? AND announcement_pid=?",
            Integer.class,getTestTenant().getId(),pid));
        org.junit.jupiter.api.Assertions.assertThrows(com.auraboot.framework.exception.ConflictException.class,
            ()->lifecycle.execute("announcement:create_announcement",null,Map.of("title","Changed"),operation,null));
        Map<String,Object> saved=lifecycle.execute("announcement:update_announcement",pid,
            Map.of("title","Edited"),UniqueIdGenerator.generate(),1);
        assertEquals(2,saved.get("row_version"));
        org.junit.jupiter.api.Assertions.assertThrows(com.auraboot.framework.exception.ConflictException.class,
            ()->lifecycle.execute("announcement:update_announcement",pid,Map.of("title","Stale"),UniqueIdGenerator.generate(),1));
        assertEquals("Edited",jdbc.queryForObject("SELECT title FROM ab_announcement WHERE tenant_id=? AND pid=?",
            String.class,getTestTenant().getId(),pid));
        org.junit.jupiter.api.Assertions.assertThrows(com.auraboot.framework.exception.BusinessException.class,
            ()->lifecycle.execute("announcement:update_announcement",pid,Map.of("status","published"),UniqueIdGenerator.generate(),2));
        org.junit.jupiter.api.Assertions.assertThrows(com.auraboot.framework.exception.BusinessException.class,
            ()->lifecycle.execute("announcement:publish",pid,Map.of(),UniqueIdGenerator.generate(),2));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ab_announcement_version WHERE tenant_id=? AND announcement_pid=?",
            Integer.class,getTestTenant().getId(),pid));
        String deletion=UniqueIdGenerator.generate();
        Map<String,Object> deleted=lifecycle.execute("announcement:delete_announcement",pid,Map.of(),deletion,2);
        assertEquals(deleted,lifecycle.execute("announcement:delete_announcement",pid,Map.of(),deletion,2));
        assertTrue(jdbc.queryForObject("SELECT deleted_flag FROM ab_announcement WHERE tenant_id=? AND pid=?",
            Boolean.class,getTestTenant().getId(),pid));
        assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM ab_announcement_audit WHERE tenant_id=? AND announcement_pid=?",
            Integer.class,getTestTenant().getId(),pid));
    }

    @Test
    void draftsNeverAppearInRecipientRestList() throws Exception {
        identity();
        String title="Private draft "+UniqueIdGenerator.generate();
        lifecycle.execute("announcement:create_announcement",null,Map.of("title",title),UniqueIdGenerator.generate(),null);
        MvcResult result=mockMvc.perform(get("/api/announcements").param("limit","100"))
            .andExpect(status().isOk()).andReturn();
        for(JsonNode record:objectMapper.readTree(result.getResponse().getContentAsString()).path("data")) {
            org.junit.jupiter.api.Assertions.assertNotEquals(title,record.path("title").asText());
        }
    }

    @Autowired private com.auraboot.framework.workbench.service.AnnouncementAudienceService audiences;
    @Autowired private com.auraboot.framework.workbench.service.AnnouncementReleaseService releaseService;
    @Autowired private com.auraboot.framework.workbench.mapper.AnnouncementReleaseMapper releaseMapper;
    @Autowired private com.auraboot.framework.workbench.service.AnnouncementDeliveryConsumer deliveryConsumer;
    @Autowired private com.auraboot.framework.workbench.service.AnnouncementCodec announcementCodec;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private void consume(com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope event) {
        new org.springframework.transaction.support.TransactionTemplate(transactionManager)
            .executeWithoutResult(status -> deliveryConsumer.consume(event));
    }

    @Autowired private com.auraboot.framework.workbench.service.AnnouncementReaderService recipientReader;
    @Autowired private com.auraboot.framework.inbox.service.InboxService inbox;
    @Autowired private com.auraboot.framework.notification.service.NotificationQueryService notifications;
    @Autowired private com.auraboot.framework.tenant.service.TenantMemberService memberships;
    @Autowired private com.auraboot.framework.user.service.UserService users;
    @Autowired private com.auraboot.framework.meta.mapper.AsyncTaskMapper asyncTasks;

    @Test
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void explicitReadingAndAcknowledgementPreserveFirstFactsAndCannotBeDismissed() {
        identity();String operation=UniqueIdGenerator.generate();
        var policy=Map.of("audience",Map.of("kind","all"),"exemptionReason","Recipient verification","acknowledgementRequired",true);
        var created=lifecycle.execute("announcement:create_announcement",null,Map.of("title","Receipt "+operation,"content","Frozen receipt body","policy",policy),operation,null);
        String pid=(String)created.get("pid");var accepted=releaseService.execute("announcement:publish",pid,Map.of(),operation+"-publish",1);
        String version=(String)accepted.get("versionPid");
        new com.auraboot.framework.workbench.service.AnnouncementReleaseWorker(releaseService).tick();identity();
        var detail=recipientReader.detail(version);String hash=(String)detail.get("snapshotHash");
        assertEquals("Frozen receipt body",detail.get("content"));assertEquals("unread",detail.get("readState"));
        assertEquals(null,jdbc.queryForObject("SELECT first_read_at FROM ab_announcement_recipient WHERE tenant_id=? AND version_pid=? AND user_pid=?",java.time.Instant.class,getTestTenant().getId(),version,getTestUser().getPid()));
        org.junit.jupiter.api.Assertions.assertThrows(com.auraboot.framework.exception.BusinessException.class,()->recipientReader.mark(version,Map.of("snapshotHash",hash),true));
        org.junit.jupiter.api.Assertions.assertThrows(com.auraboot.framework.exception.BusinessException.class,()->recipientReader.mark(version,Map.of("snapshotHash","wrong"),false));
        org.junit.jupiter.api.Assertions.assertThrows(com.auraboot.framework.exception.BusinessException.class,()->recipientReader.mark(version,Map.of("snapshotHash",hash,"userPid","someone-else"),false));
        var item=new com.auraboot.framework.inbox.model.InboxItem();item.setTenantId(getTestTenant().getId());item.setUserId(getTestUser().getId());
        item.setItemType("announcement_ack");item.setSourceType("announcement");item.setSourceId(version);item.setTitle("Receipt pending");item.setClientItemId("recipient-it:"+version);
        inbox.createItem(item);int pending=inbox.getPendingCount(getTestUser().getId(),getTestTenant().getId());
        inbox.markAllRead(getTestUser().getId(),getTestTenant().getId());assertEquals(pending,inbox.getPendingCount(getTestUser().getId(),getTestTenant().getId()));
        org.junit.jupiter.api.Assertions.assertThrows(com.auraboot.framework.exception.BusinessException.class,()->inbox.dismiss(item.getId(),getTestUser().getId(),getTestTenant().getId()));
        assertEquals(0,inbox.batchMarkActed(List.of(item.getId()),getTestUser().getId(),getTestTenant().getId(),"done"));
        var read=recipientReader.mark(version,Map.of("snapshotHash",hash),false);
        assertNotNull(read.get("first_read_at"));assertEquals(read.get("first_read_at"),recipientReader.mark(version,Map.of("snapshotHash",hash),false).get("first_read_at"));
        assertEquals("pending",read.get("acknowledgementState"));
        var ack=recipientReader.mark(version,Map.of("snapshotHash",hash),true);
        assertEquals("completed",ack.get("acknowledgementState"));assertEquals(read.get("first_read_at"),ack.get("first_read_at"));
        assertEquals(ack.get("acknowledged_at"),recipientReader.mark(version,Map.of("snapshotHash",hash),true).get("acknowledged_at"));
        assertEquals(pending-1,inbox.getPendingCount(getTestUser().getId(),getTestTenant().getId()));
        assertEquals("acted",inbox.getItem(item.getId(),getTestUser().getId(),getTestTenant().getId()).getStatus());
        lifecycle.execute("announcement:archive",pid,Map.of(),operation+"-withdraw",3);
        org.junit.jupiter.api.Assertions.assertThrows(com.auraboot.framework.exception.BusinessException.class,()->recipientReader.detail(version));
        org.junit.jupiter.api.Assertions.assertThrows(com.auraboot.framework.exception.BusinessException.class,()->recipientReader.mark(version,Map.of("snapshotHash",hash),true));
    }

    @Test
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void frozenExemptionPublishesToRealMembersAndProjectsSeparateNotificationAndAcknowledgement() {
        identity();
        String operation=UniqueIdGenerator.generate();
        var policy=Map.of("audience",Map.of("kind","all"),"exemptionReason","Tenant policy update", "acknowledgementRequired",true);
        var created=lifecycle.execute("announcement:create_announcement",null,
            Map.of("title","Policy "+operation,"content","Read the updated tenant policy.","policy",policy),operation,null);
        String pid=(String)created.get("pid");
        var accepted=releaseService.execute("announcement:publish",pid,Map.of(),operation+"-publish",1);
        assertEquals(accepted,releaseService.execute("announcement:publish",pid,Map.of(),operation+"-publish",1));
        String version=(String)accepted.get("versionPid");
        assertEquals("unpublished",accepted.get("status"));
        MetaContext.clear();
        Map<String,Object> claim=releaseService.claim();
        assertNotNull(claim);
        assertEquals(version,claim.get("version_pid"));
        releaseService.perform(claim);
        org.junit.jupiter.api.Assertions.assertFalse(MetaContext.exists());
        identity();
        assertEquals("published",jdbc.queryForObject("SELECT status FROM ab_announcement WHERE tenant_id=? AND pid=?",String.class,getTestTenant().getId(),pid));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM ab_announcement_recipient WHERE tenant_id=? AND version_pid=? AND user_pid=? AND member_pid=?",Integer.class,
            getTestTenant().getId(),version,getTestUser().getPid(),getTestTenantMember().getPid()));
        String frozen=jdbc.queryForObject("SELECT snapshot::text FROM ab_announcement_version WHERE tenant_id=? AND pid=?",String.class,getTestTenant().getId(),version);
        assertEquals("Read the updated tenant policy.",announcementCodec.object(frozen).get("content"));
        String batch=(String)accepted.get("releaseAttemptPid");
        var root=new com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope("1.0",batch,
            com.auraboot.framework.workbench.service.AnnouncementReleaseService.RELEASED_EVENT,"auraboot.announcement",version,
            java.time.Instant.now(),getTestTenant().getId(),batch,null,"announcement:"+version,1,
            Map.of("versionPid",version,"batchPid",batch),Map.of());
        consume(root);
        var payload=Map.<String,Object>of("versionPid",version,"batchPid",batch,"userPid",getTestUser().getPid());
        // Projection is an independent event and can complete before notification persistence.
        var ack=new com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope("1.0","ack:"+batch,
            com.auraboot.framework.workbench.service.AnnouncementDeliveryConsumer.ACK,"auraboot.announcement",version,
            root.occurredAt(),getTestTenant().getId(),batch,batch,"ack:"+version,1,payload,Map.of());
        consume(ack);
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM ab_inbox_item WHERE tenant_id=? AND user_id=? AND source_id=? AND item_type='announcement_ack' AND status='pending'",Integer.class,
            getTestTenant().getId(),getTestUser().getId(),version));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ab_notification WHERE tenant_id=? AND source_id=?",Integer.class,getTestTenant().getId(),version));
        var delivery=new com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope("1.0",batch+":"+getTestUser().getPid(),
            com.auraboot.framework.workbench.service.AnnouncementDeliveryConsumer.DELIVER,"auraboot.announcement",version,
            root.occurredAt(),getTestTenant().getId(),batch,batch,"delivery:"+version,1,payload,Map.of());
        consume(delivery);consume(delivery);
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM ab_notification WHERE tenant_id=? AND source_id=? AND user_id=?",Integer.class,
            getTestTenant().getId(),version,getTestUser().getId()));
        assertEquals("completed",jdbc.queryForObject("SELECT status FROM ab_announcement_delivery WHERE tenant_id=? AND batch_pid=? AND user_pid=?",String.class,
            getTestTenant().getId(),batch,getTestUser().getPid()));
        jdbc.update("UPDATE ab_notification SET source_type='ANNOUNCEMENT' WHERE tenant_id=? AND source_id=?",getTestTenant().getId(),version);
        var query=new com.auraboot.framework.notification.dto.NotificationQueryRequest();query.setPageSize(100);
        org.assertj.core.api.Assertions.assertThat(notifications.listByUser(getTestUser().getId(),query).getRecords())
            .extracting(com.auraboot.framework.notification.dto.NotificationDTO::getSourceId).contains(version);
        Long itemId=jdbc.queryForObject("SELECT id FROM ab_inbox_item WHERE tenant_id=? AND source_id=? AND user_id=? AND item_type='announcement_ack'",Long.class,
            getTestTenant().getId(),version,getTestUser().getId());
        try {
            new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status->{
                status.setRollbackOnly();
                jdbc.update("UPDATE ab_tenant_member SET deleted_flag=TRUE,status='inactive' WHERE id=? AND tenant_id=?",getTestTenantMember().getId(),getTestTenant().getId());
                var replacement=memberships.addMember(getTestUser().getId(),getTestTenant().getId(),"active");
                org.assertj.core.api.Assertions.assertThat(replacement.getPid()).isNotEqualTo(getTestTenantMember().getPid());
                MetaContext.setMemberId(replacement.getId());
                var softly=new org.assertj.core.api.SoftAssertions();
                softly.assertThat(notifications.listByUser(getTestUser().getId(),query).getRecords())
                    .extracting(com.auraboot.framework.notification.dto.NotificationDTO::getSourceId).doesNotContain(version);
                softly.assertThat(inbox.getItem(itemId,getTestUser().getId(),getTestTenant().getId())).isNull();
                softly.assertThat(inbox.listByUser(getTestUser().getId(),getTestTenant().getId(),"announcement_ack",null,1,100).getRecords())
                    .extracting(com.auraboot.framework.inbox.model.InboxItem::getSourceId).doesNotContain(version);
                softly.assertAll();
            });
        } finally {identity();}

    }

    @Test
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void futureReleaseCancellationRetainsVersionButCannotPublish() {
        identity();
        String operation=UniqueIdGenerator.generate();
        var created=lifecycle.execute("announcement:create_announcement",null,Map.of("title","Scheduled "+operation,"content","Scheduled content",
            "policy",Map.of("audience",Map.of("kind","all"),"exemptionReason","Scheduled policy","scheduledAt",java.time.Instant.now().plusSeconds(86400).toString())),operation,null);
        String pid=(String)created.get("pid");
        var accepted=releaseService.execute("announcement:publish",pid,Map.of(),operation+"-release",1);
        var canceled=releaseService.execute("announcement:cancel_schedule",pid,Map.of(),operation+"-cancel",2);
        assertEquals("withdrawn",canceled.get("status"));
        assertEquals("canceled",jdbc.queryForObject("SELECT status FROM ab_announcement_release WHERE tenant_id=? AND version_pid=?",String.class,getTestTenant().getId(),accepted.get("versionPid")));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ab_announcement_recipient WHERE tenant_id=? AND version_pid=?",Integer.class,getTestTenant().getId(),accepted.get("versionPid")));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"command-handler","export"})
    void publicAsyncSubmissionCannotSelectACommandHandlerExecutionIdentity(String taskType) throws Exception {
        var input=Map.of("taskType",taskType,"taskName","Protected announcement dispatch",
            "inputParams",Map.of("handlerCode","announcement:publish","tenantId",getTestTenant().getId(),"userId",getTestUser().getId()));
        mockMvc.perform(post("/api/async-tasks").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(input)))
            .andExpect(status().isForbidden());
    }
    @Test void publicAsyncTaskReadAndDeleteRequireThePersistedTenantAndOwner() throws Exception {
        var other=users.signUp("async-owner-"+UniqueIdGenerator.generate()+"@example.com","Test2026x");
        memberships.addMember(other.getId(),getTestTenant().getId(),"active");
        var task=new com.auraboot.framework.meta.entity.AsyncTask();
        task.setTenantId(getTestTenant().getId());task.setCreatedBy(other.getId());task.setTaskCode(UniqueIdGenerator.generate());
        task.setTaskType("command-handler");task.setTaskName("Private owned work");task.setStatus("completed");task.setProgress(100);
        task.setCreatedAt(java.time.Instant.now());task.setCompletedAt(java.time.Instant.now());
        task.setInputParams(objectMapper.valueToTree(Map.of("privatePayload","Private business source")));asyncTasks.insert(task);
        String foreignCode=task.getTaskCode();
        assertEquals(other.getId(),jdbc.queryForObject("SELECT created_by FROM ab_async_task WHERE tenant_id=? AND task_code=?",Long.class,getTestTenant().getId(),foreignCode));
        task.setId(null);task.setTaskCode(UniqueIdGenerator.generate());task.setCreatedBy(getTestUser().getId());asyncTasks.insert(task);
        mockMvc.perform(get("/api/async-tasks/"+task.getTaskCode())).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.taskCode").value(task.getTaskCode()));
        mockMvc.perform(get("/api/async-tasks/"+foreignCode)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/async-tasks/"+foreignCode+"/cancel")).andExpect(status().isForbidden());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/async-tasks/"+foreignCode))
            .andExpect(status().isForbidden());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM ab_async_task WHERE tenant_id=? AND task_code=?",Integer.class,getTestTenant().getId(),foreignCode));
    }

}
