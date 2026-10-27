package com.auraboot.framework.workbench;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.infrastructure.storage.StorageProvider;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.impl.pipeline.*;
import com.auraboot.framework.plugin.extension.*;
import com.auraboot.framework.plugin.extension.AnnouncementSealedContentAccessor.*;
import com.auraboot.framework.plugin.extension.approval.ApprovalContract.*;
import com.auraboot.framework.plugin.pf4j.AnnouncementSealedContentAccessorFactory;
import com.auraboot.framework.workbench.mapper.*;
import com.auraboot.framework.workbench.service.*;
import com.auraboot.framework.permission.service.*;
import com.auraboot.framework.permission.dto.PermissionCreateRequest;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.service.*;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Real PG + real local storage. Controlled approval-binding fixtures are not an actual BPM engine approval. */
@SpringBootTest(classes=TestApplication.class)
@ActiveProfiles("integration-test")
class AnnouncementSealedContentIT {
    static final Path STORAGE=storageDirectory();
    static Path storageDirectory(){try{return Files.createTempDirectory("aura-seal-slot72-");}catch(java.io.IOException e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){registry.add("aura.storage.local.base-path",STORAGE::toString);}
    @Autowired AnnouncementContentSealService content;
    @Autowired AnnouncementSealedContentAccessorFactory factory;
    @Autowired AnnouncementLifecycleMapper roots;
    @Autowired AnnouncementReleaseMapper releases;
    @Autowired AnnouncementApprovalMapper approvals;
    @Autowired AnnouncementCodec codec;
    @Autowired FileService files;
    @Autowired StorageProvider storage;
    @Autowired UserService users;
    @Autowired TenantService tenants;
    @Autowired TenantMemberService members;
    @Autowired IdentityDirectoryAccessor identities;
    @Autowired PermissionService permissions;
    @Autowired UserPermissionService userPermissions;
    @Autowired com.auraboot.framework.permission.service.impl.PermissionSnapshotCache permissionSnapshots;
    @Autowired RolePermissionMapper rolePermissions;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    User user;Tenant tenant;Long memberId;String memberPid,root,version,hash,filePid,fileKey;Map<String,Object> snapshot;
    @BeforeEach void setup() {
        String suffix=UniqueIdGenerator.generate().toLowerCase();
        user=users.signUp("sealed-"+suffix+"@example.test","test-password-123");
        var t=new Tenant();t.setPid(UniqueIdGenerator.generate());t.setName("sealed-"+suffix);t.setDisplayName("Sealed content IT");
        t.setStatus("active");t.setDeletedFlag(false);t.setCreatedAt(Instant.now());t.setUpdatedAt(Instant.now());tenant=tenants.createTenant(t);
        members.addMember(user.getId(),tenant.getId(),"active");var member=members.findByTenantIdAndUserId(tenant.getId(),user.getId());
        memberId=member.getId();memberPid=member.getPid();actor();
        long roleId=com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();String role=UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_role(id,pid,tenant_id,name,deleted_flag) VALUES(?,?,?,'sealed-publisher',false)",roleId,role,tenant.getId());
        jdbc.update("INSERT INTO ab_user_role(id,pid,member_id,tenant_id,role_id,deleted_flag) VALUES(?,?,?,?,?,false)",roleId+1,UniqueIdGenerator.generate(),memberId,tenant.getId(),roleId);
        var request=new PermissionCreateRequest();request.setCode("announcement.announcement.publish");request.setName("Publish official announcement");
        request.setResourceType("model");request.setResourceCode("announcement.announcement");request.setAction("publish");request.setSource("integration_test");
        var permission=permissions.create(request);var grant=new RolePermission();grant.setPid(UniqueIdGenerator.generate());grant.setTenantId(tenant.getId());
        grant.setRoleId(roleId);grant.setPermissionId(permission.getId());grant.setGrantType("grant");grant.setStatus("active");grant.setPriority(100);grant.setDeletedFlag(false);
        grant.setCreatedAt(Instant.now());grant.setUpdatedAt(Instant.now());rolePermissions.insert(grant);
        userPermissions.evictPermissionDefinitions(tenant.getId());userPermissions.evictRoleUsers(tenant.getId(),roleId);
        var uploaded=files.uploadFile(new MockMultipartFile("file","sealed.txt","text/plain","sealed byte payload".getBytes(java.nio.charset.StandardCharsets.UTF_8)),user.getId());
        filePid=uploaded.getFileId();var file=files.findByPid(filePid);fileKey=file.getFileName();
        root=UniqueIdGenerator.generate();version=UniqueIdGenerator.generate();
        snapshot=Map.of("title","制度 Title ","content","<p>exact raw é\r\n正文</p>","policy",Map.of("attachmentPids",List.of(filePid),
                "attachmentMetadata",List.of(Map.of("fileId",filePid,"name",file.getOriginalName(),"size",file.getFileSize(),"type",file.getMimeType()))));
        hash=codec.hash(snapshot);
    }
    @AfterEach void clear(){MetaContext.clear();}
    void actor(){MetaContext.setContext(tenant.getId(),user.getId(),user.getPid(),user.getUserName());MetaContext.setMemberId(memberId);}
    ExactVersion expected(){return new ExactVersion(root,version,hash,AnnouncementDocumentCanonicalizer.SCHEMA);}
    void freeze(boolean capture) {
        new TransactionTemplate(transactions).executeWithoutResult(status->{
            content.lockActor();assertEquals(1,roots.insertDraft(tenant.getId(),user.getId(),root,"制度 Title ","<p>raw</p>","normal",false,null));
            assertNotNull(roots.lock(tenant.getId(),root));assertEquals(1,releases.freeze(tenant.getId(),root,version,1,hash,codec.encode(snapshot),user.getId()));
            assertTrue(files.lockRetention(filePid));
            if(capture)content.captureFrozenVersion(tenant.getId(),root,version,hash,codec.encode(snapshot));
            String submission=UniqueIdGenerator.generate(),result=UniqueIdGenerator.generate(),definition=UniqueIdGenerator.generate();
            var binding=new SubmittedApproval(submission,"fixture-operation",new BusinessRef("announcement",root,version),"snapshot",hash,"announcement.v1",
                    new DefinitionRef(definition,1,"a".repeat(64)),StrictPolicy.required(),user.getPid(),"制度 Title ",Instant.now());
            assertEquals(1,approvals.insert(tenant.getId(),submission,root,version,user.getId(),memberPid,user.getPid(),"controlled-test-fixture","Fixture definition",codec.encode(binding),codec.hash(binding)));
            var terminal=new TerminalResult(submission,binding.businessRef(),hash,"fixture-instance",binding.definition(),"approved",result,1,Instant.now(),"CONTROLLED_TEST_FIXTURE");
            jdbc.update("UPDATE ab_announcement_approval_submission SET state='approved',sync_state='completed',instance_pid='fixture-instance',provider_revision=1,terminal_result_pid=?,terminal_result=CAST(? AS jsonb) WHERE tenant_id=? AND pid=?",
                    result,codec.encode(terminal),tenant.getId(),submission);
            jdbc.update("INSERT INTO ab_announcement_version_control(tenant_id,version_pid,approval_state,publication_state,authorization_basis,current_submission_pid,published_at) VALUES(?,?,'approved','published','approved',?,clock_timestamp())",
                    tenant.getId(),version,submission);
            jdbc.update("UPDATE ab_announcement SET current_version_pid=?,active_version_pid=?,status='published',published_at=clock_timestamp() WHERE tenant_id=? AND pid=?",version,version,tenant.getId(),root);
        });
    }
    void command(Consumer<AnnouncementSealedContentAccessor> action) {
        new TransactionTemplate(transactions).executeWithoutResult(status->{
            var definition=new CommandDefinition();definition.setCode("policy:publish");definition.setModelCode("policy");
            var request=new CommandExecuteRequest();request.setTargetRecordId("policy-target");
            var context=CommandPipelineContext.builder().commandCode("policy:publish").command(definition).request(request).tenantId(tenant.getId()).userId(user.getId()).build();
            context.setAuthorizationVerdict(CommandAuthorizationVerdict.authorized("policy.publish"));context.setTargetRecordReadable(true);
            context.setPermitPlan(new CommandPermitPlan(CommandPermitPlan.Decision.PERMIT,null,null,"policy-target",CommandPermitPlan.ScopeGrade.TARGET,1L));
            MetaContext.runWithCommandPermitPlan("TARGET",1L,"policy","policy-target",()->MetaContext.runWithAuthorizedCommandCode("policy:publish",()->{
                try(var scope=factory.forCommand(context)){assertNotNull(scope);action.accept(scope.accessor());}
            }));
        });
    }
    @Test void publisherNotARecipientGetsExactContentAndOfficialBindingFixtureInSamePhysicalTransaction() {
        freeze(true);
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ab_announcement_recipient WHERE tenant_id=? AND version_pid=?",Integer.class,tenant.getId(),version));
        command(bridge->{var proof=bridge.requirePublishedContentForCommand(expected());assertEquals(snapshot.get("content"),proof.document().body());
            assertEquals("制度 Title ",proof.document().title());assertEquals(memberPid,proof.actorMemberPid());assertNotEquals(hash,proof.encoded().documentDigest());
            assertEquals(files.findByPid(filePid).getFileSize().longValue(),proof.document().attachments().getFirst().byteLength());
            assertEquals(AnnouncementDocumentCanonicalizer.SourceIdentityKind.SEAL_OBSERVED_CONTENT,proof.document().attachments().getFirst().sourceIdentityKind());});
    }
    @Test void oldMissingSealCannotBeBackfilledByLookupOrLaterTransaction() {
        freeze(false);assertThrows(IllegalStateException.class,()->command(bridge->bridge.requirePublishedContentForCommand(expected())));
        assertThrows(org.springframework.dao.DataAccessException.class,()->new TransactionTemplate(transactions).executeWithoutResult(status->{
            content.lockActor();roots.lock(tenant.getId(),root);content.captureFrozenVersion(tenant.getId(),root,version,hash,codec.encode(snapshot));
        }));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ab_announcement_content_seal WHERE tenant_id=?",Integer.class,tenant.getId()));
    }
    @Test void sealEvidenceCannotBeUpdatedOrDeleted() {
        freeze(true);
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE ab_announcement_content_seal SET document_digest=? WHERE tenant_id=?", "b".repeat(64),tenant.getId()));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("DELETE FROM ab_announcement_content_seal WHERE tenant_id=?",tenant.getId()));
    }
    @Test void sameTransactionWithdrawalAtCommitRollsBackCallerMutation() {
        freeze(true);
        assertThrows(RuntimeException.class,()->command(bridge->{bridge.requirePublishedContentForCommand(expected());
            jdbc.update("UPDATE ab_announcement_version_control SET publication_state='withdrawn' WHERE tenant_id=? AND version_pid=?",tenant.getId(),version);
        }));
        assertEquals("published",jdbc.queryForObject("SELECT publication_state FROM ab_announcement_version_control WHERE tenant_id=? AND version_pid=?",String.class,tenant.getId(),version));
    }
    @Test void sameLocatorByteReplacementIsNotAProviderGenerationAndIsRejected() throws Exception {
        freeze(true);Files.write(STORAGE.resolve(fileKey),"tampered byte data!".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class,()->command(bridge->bridge.requirePublishedContentForCommand(expected())));
    }
    @Test void sourceByteChangeAfterProofIsRejectedAtCommit() {
        freeze(true);
        assertThrows(RuntimeException.class,()->command(bridge->{bridge.requirePublishedContentForCommand(expected());
            try {Files.write(STORAGE.resolve(fileKey),"tampered byte data!".getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}
        }));
    }
    @Test void anActiveOwnerWithoutPublishCannotBorrowContentAuthority() {
        freeze(true);jdbc.update("UPDATE ab_role_permission SET deleted_flag=true WHERE tenant_id=?",tenant.getId());
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->command(bridge->bridge.requirePublishedContentForCommand(expected())));
    }
    @Test void publisherPermissionRevocationCannotReuseAProofAtCommit() {
        freeze(true);assertTrue(userPermissions.hasPermission(user.getId(),"announcement.announcement.publish"));
        assertThrows(RuntimeException.class,()->command(bridge->{bridge.requirePublishedContentForCommand(expected());
            jdbc.update("UPDATE ab_role_permission SET deleted_flag=true WHERE tenant_id=?",tenant.getId());
        }));
        assertTrue(onAnotherThread(()->userPermissions.hasPermission(user.getId(),"announcement.announcement.publish")));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ab_role_permission WHERE tenant_id=? AND deleted_flag=true",Integer.class,tenant.getId()));
    }
    @Test void aConcurrentRootWriterCannotPassTheProofRootLockBeforeCommit() {
        freeze(true);
        command(bridge->{bridge.requirePublishedContentForCommand(expected());
            assertTrue(onAnotherThread(()->{
                try {
                    new TransactionTemplate(transactions).executeWithoutResult(status->{
                        jdbc.execute("SET LOCAL lock_timeout='200ms'");
                        jdbc.queryForMap("SELECT pid FROM ab_announcement WHERE tenant_id=? AND pid=? FOR UPDATE",tenant.getId(),root);
                    });
                    return false;
                } catch(org.springframework.dao.DataAccessException blocked) {
                    if(blocked.getMostSpecificCause() instanceof java.sql.SQLException sql && "55P03".equals(sql.getSQLState())) return true;
                    throw blocked;
                }
            }));
        });
        assertNotNull(new TransactionTemplate(transactions).execute(status->roots.lock(tenant.getId(),root)));
    }
    @Test void uncommittedFreshGrantIsNeverPublishedToAnotherThreadOrRollbackCache() {
        var definition=new PermissionCreateRequest();definition.setCode("announcement.announcement.manage");definition.setName("Manage official announcement");
        definition.setResourceType("model");definition.setResourceCode("announcement.announcement");definition.setAction("manage");definition.setSource("integration_test");
        var created=permissions.create(definition);userPermissions.evictPermissionDefinitions(tenant.getId());
        assertFalse(userPermissions.hasPermission(user.getId(),definition.getCode()));
        long role=jdbc.queryForObject("SELECT role_id FROM ab_user_role WHERE tenant_id=? AND member_id=?",Long.class,tenant.getId(),memberId);
        new TransactionTemplate(transactions).executeWithoutResult(status->{
            var grant=new RolePermission();grant.setPid(UniqueIdGenerator.generate());grant.setTenantId(tenant.getId());grant.setRoleId(role);
            grant.setPermissionId(created.getId());grant.setGrantType("grant");grant.setStatus("active");grant.setPriority(100);grant.setDeletedFlag(false);
            grant.setCreatedAt(Instant.now());grant.setUpdatedAt(Instant.now());rolePermissions.insert(grant);
            assertTrue(permissionSnapshots.withFreshReads(()->userPermissions.hasPermission(user.getId(),definition.getCode())));
            assertFalse(onAnotherThread(()->userPermissions.hasPermission(user.getId(),definition.getCode())));
            status.setRollbackOnly();
        });
        assertFalse(onAnotherThread(()->userPermissions.hasPermission(user.getId(),definition.getCode())));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ab_role_permission WHERE tenant_id=? AND permission_id=?",Integer.class,tenant.getId(),created.getId()));
    }
    boolean onAnotherThread(java.util.function.Supplier<Boolean> task) {
        var identity=MetaContext.snapshot();var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try{return executor.submit(()->{MetaContext.restore(identity);try{return task.get();}finally{MetaContext.clear();}}).get(10,java.util.concurrent.TimeUnit.SECONDS);}
        catch(Exception failure){throw new IllegalStateException(failure);}
        finally{executor.shutdownNow();}
    }

}
