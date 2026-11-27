package com.auraboot.framework.file.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.infrastructure.storage.StorageProvider;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.meta.service.impl.pipeline.*;
import com.auraboot.framework.plugin.extension.FileAccessor;
import com.auraboot.framework.plugin.pf4j.CommandTargetFileAccessorFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;

/** Real Core metadata, SQL, mappers, physical transactions and deletion arbitration. No startup DDL. */
@SpringBootTest(classes=TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
class CommandTargetFileAppendIT {
    @Autowired private com.auraboot.framework.user.service.UserService fixtureUsers;
    @Autowired private com.auraboot.framework.tenant.service.TenantService fixtureTenants;
    @Autowired private com.auraboot.framework.tenant.service.TenantMemberService fixtureMembers;
    private com.auraboot.framework.user.dao.entity.User fixtureUser;
    private com.auraboot.framework.tenant.dao.entity.TenantMember fixtureMember;
    @Autowired private com.auraboot.framework.permission.service.UserPermissionService fixturePermissions;
    private com.auraboot.framework.integration.NativePermissionFixture nativeAuthority;
    @AfterAll void releaseOwnedAuthority() { if (nativeAuthority != null) nativeAuthority.close(); }

    private Long TENANT, USER, FOREIGN_TENANT, OTHER_USER;
    @Autowired private javax.sql.DataSource fixtureDataSource;
    @Autowired private org.springframework.boot.data.redis.autoconfigure.DataRedisProperties fixtureRedis;
    @Autowired CommandTargetFileAccessorFactory factory;
    @Autowired FileService files;
    @Autowired MetaModelService models;
    @Autowired SchemaManagementService schema;
    @Autowired DynamicDataService data;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean StorageProvider storage;
    private final AtomicLong seq=new AtomicLong(System.currentTimeMillis());
    private String model, table, command;

    @BeforeAll void publishFixture() throws Exception {
        com.auraboot.framework.integration.DeclaredTestInfrastructure.assertSelected(fixtureDataSource, fixtureRedis);
        String suffix=com.auraboot.framework.common.util.UniqueIdGenerator.generate().toLowerCase();
        fixtureUser=fixtureUsers.signUp("file-append-"+suffix+"@example.test","test-password-123");
        var tenant=new com.auraboot.framework.tenant.dao.entity.Tenant();
        tenant.setPid(com.auraboot.framework.common.util.UniqueIdGenerator.generate());
        tenant.setName("file-append-"+suffix);tenant.setDisplayName("Owned file append fixture");
        tenant.setStatus("active");tenant.setDeletedFlag(false);
        tenant.setCreatedAt(Instant.now());tenant.setUpdatedAt(Instant.now());
        tenant=fixtureTenants.createTenant(tenant);TENANT=tenant.getId();USER=fixtureUser.getId();
        fixtureMember=fixtureMembers.addMember(USER,TENANT,"active");
        var otherUser = fixtureUsers.signUp("file-append-other-" + suffix + "@example.test", "test-password-123");
        OTHER_USER = otherUser.getId();
        fixtureMembers.addMember(OTHER_USER, TENANT, "active");
        FOREIGN_TENANT = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        jdbc.update("INSERT INTO ab_tenant (id,pid,name,status,deleted_flag) VALUES (?,?,?,'active',FALSE)",
                FOREIGN_TENANT, com.auraboot.framework.common.util.UniqueIdGenerator.generate(), "Foreign append tenant " + suffix);
        actor();
        model="file_append_"+Long.toString(System.currentTimeMillis(),36); table="mt_"+model; command=model+":repair";
        var photos=FieldDefinition.builder().code("photos").columnName("photos").dataType("file").length(32768).maxLength(32768)
                .extraProps(Map.of("multiple",true,"maxCount",10)).allowedWriterCommands(List.of(command)).build();
        var legacy=FieldDefinition.builder().code("legacy_photos").columnName("legacy_photos").dataType("file").build();
        var frozen=FieldDefinition.builder().code("frozen_photos").columnName("frozen_photos").dataType("file")
                .immutable(true).allowedWriterCommands(List.of(command)).build();
        var evidence=FieldDefinition.builder().code("evidence").columnName("evidence").dataType("string").maxLength(128).build();
        models.saveDefinition(ModelDefinition.builder().code(model).tableName(table).status("published")
                .modelType("entity").displayName("Command target append IT").fields(List.of(photos,evidence,legacy,frozen)).build());
        // Explicit model initialization through the official schema service, never ad-hoc ALTER.
        var result=schema.createTableByModel(model);
        assertThat(result.isSuccess()).as(result.getErrorMessage()).isTrue();
        assertThat(models.getModelDefinition(model).orElseThrow().getFields()).anyMatch(f->f.getCode().equals("photos"));
        assertThat(jdbc.queryForObject("SELECT data_type FROM information_schema.columns WHERE table_name=? AND column_name='photos'",
                String.class,table)).isEqualTo("jsonb");
        nativeAuthority = new com.auraboot.framework.integration.NativePermissionFixture(jdbc, fixturePermissions);
        nativeAuthority.grantModel(model, "create", "read", "update", "delete");
        MetaContext.clear();
    }
    @AfterEach void clear() { MetaContext.clear(); }
    private void actor() {
        MetaContext.setContext(TENANT,USER,fixtureUser.getPid(),fixtureUser.getUserName());
        MetaContext.setMemberId(fixtureMember.getId());
    }
    private String parent() {
        actor();
        return MetaContext.runWithCommandPermitPlan("ALL",null,model,null,()->
                MetaContext.runWithAuthorizedCommandCode(command,()->String.valueOf(data.create(model,Map.of("evidence","before")).get("pid"))));
    }
    private String upload(long tenant,long owner,String status) {
        long id=seq.incrementAndGet(); String pid="FILE-APPEND-"+id;
        Timestamp now=Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO ab_file (id,pid,tenant_id,file_name,original_name,file_size,mime_type,storage_type,upload_time,created_by,status,created_time,updated_time,deleted_flag,retention_locked) VALUES (?,?,?,?,?,10,'application/pdf','local',?,?,?,?,?,false,false)",
                id,pid,tenant,"append-"+id+".pdf","append.pdf",now,owner,status,now,now);
        return pid;
    }
    private CommandPipelineContext context() {
        var definition=new CommandDefinition(); definition.setCode(command); definition.setModelCode(model);
        var ctx=CommandPipelineContext.builder().commandCode(command).tenantId(TENANT).userId(USER)
                .request(new CommandExecuteRequest()).command(definition).build();
        ctx.setAuthorizationVerdict(CommandAuthorizationVerdict.authorized(model+".repair"));
        ctx.setTargetRecordReadable(true);
        ctx.setPermitPlan(new CommandPermitPlan(CommandPermitPlan.Decision.PERMIT,null,null,null,CommandPermitPlan.ScopeGrade.TARGET,null));
        return ctx;
    }
    private void command(String pid,Consumer<FileAccessor> action) {
        actor();
        try {
            var commandTransaction = new TransactionTemplate(transactions);
            // This capability owns row locks and a TARGET permit; its transaction contract is writable,
            // not an ordinary human projection. Recheck the locked row after each competing commit.
            commandTransaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
            commandTransaction.executeWithoutResult(status->{
                // This is the caller's parent lock/CAS, ahead of every file lock. It serializes
                // same-parent append calls and commits projection/evidence in the same transaction.
                Long version=jdbc.queryForObject("SELECT row_version FROM "+table+" WHERE tenant_id=? AND pid=? FOR UPDATE",Long.class,TENANT,pid);
                MetaContext.runWithCommandPermitPlan("TARGET",version,model,pid,()->
                    MetaContext.runWithAuthorizedCommandCode(command,()->{
                        data.update(model,pid,Map.of("evidence","after"));
                        try(var scope=factory.forCommand(context())) {
                            assertThat(scope).isNotNull(); action.accept(scope.accessor());
                        }
                    }));
            });
        } finally { MetaContext.clear(); }
    }
    private int relationCount(String parent) {
        return jdbc.queryForObject("SELECT count(*) FROM ab_file_relation WHERE tenant_id=? AND entity_type=? AND entity_id=? AND field_name='photos' AND deleted_flag=false",Integer.class,TENANT,model,parent);
    }
    private boolean retained(String file) { return Boolean.TRUE.equals(jdbc.queryForObject("SELECT retention_locked FROM ab_file WHERE pid=?",Boolean.class,file)); }
    private String evidence(String parent) { return jdbc.queryForObject("SELECT evidence FROM "+table+" WHERE tenant_id=? AND pid=?",String.class,TENANT,parent); }

    @Test void appendPreservesExistingRelationsAndRepeatIsIdempotent() {
        String parent=parent(), first=upload(TENANT,USER,"success"), second=upload(TENANT,USER,"success");
        String firstProjection="[\""+first+"\"]", bothProjection="[\""+first+"\",\""+second+"\"]";
        command(parent,a->{data.update(model,parent,Map.of("photos",firstProjection)); a.appendAndRetainExistingToTarget(first,"photos");});
        command(parent,a->{data.update(model,parent,Map.of("photos",bothProjection));
            a.appendAndRetainExistingToTarget(first,"photos"); a.appendAndRetainExistingToTarget(second,"photos"); a.appendAndRetainExistingToTarget(first,"photos");});
        assertThat(jdbc.queryForObject("SELECT photos = ?::jsonb FROM " + table + " WHERE tenant_id=? AND pid=?",
                Boolean.class, bothProjection, TENANT, parent)).isTrue();
        assertThat(relationCount(parent)).isEqualTo(2); assertThat(retained(first)).isTrue(); assertThat(retained(second)).isTrue();
        assertThat(evidence(parent)).isEqualTo("after");
        actor(); assertThatThrownBy(()->files.deleteFile(first,USER)).hasMessageContaining("retention");
    }
    @Test void outerFailureRollsBackRetentionRelationsProjectionAndEvidence() {
        String parent=parent(), file=upload(TENANT,USER,"success");
        assertThatThrownBy(()->command(parent,a->{data.update(model,parent,Map.of("photos","[\""+file+"\"]"));
            a.appendAndRetainExistingToTarget(file,"photos"); throw new IllegalStateException("domain evidence failed");}))
                .hasMessageContaining("domain evidence failed");
        assertThat(relationCount(parent)).isZero(); assertThat(retained(file)).isFalse(); assertThat(evidence(parent)).isEqualTo("before");
        assertThat(jdbc.queryForObject("SELECT photos FROM "+table+" WHERE pid=?",String.class,parent)).isNull();
    }
    @Test void aCaughtAppendFailureStillPreventsPartialCommit() {
        String parent=parent(), good=upload(TENANT,USER,"success"), foreign=upload(TENANT,OTHER_USER,"success");
        assertThatThrownBy(()->command(parent,a->{a.appendAndRetainExistingToTarget(good,"photos");
            assertThatThrownBy(()->a.appendAndRetainExistingToTarget(foreign,"photos")).isInstanceOf(AccessDeniedException.class);}))
                .isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertThat(relationCount(parent)).isZero(); assertThat(retained(good)).isFalse(); assertThat(evidence(parent)).isEqualTo("before");
    }
    @Test void foreignTenantOwnerAndUnfinalizedUploadsLeaveNoWrites() {
        for(String file:List.of(upload(FOREIGN_TENANT,USER,"success"),upload(TENANT,OTHER_USER,"success"),upload(TENANT,USER,"uploading"))) {
            String parent=parent();
            assertThatThrownBy(()->command(parent,a->a.appendAndRetainExistingToTarget(file,"photos"))).isInstanceOf(AccessDeniedException.class);
            assertThat(retained(file)).isFalse(); assertThat(relationCount(parent)).isZero(); assertThat(evidence(parent)).isEqualTo("before");
        }
    }
    @Test void requiresNewCannotReuseOriginalCapabilityOrCommitPartialParent() {
        String parent=parent(), file=upload(TENANT,USER,"success");
        assertThatThrownBy(()->command(parent,a->{
            var isolated=new TransactionTemplate(transactions); isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            isolated.executeWithoutResult(s->assertThatThrownBy(()->a.appendAndRetainExistingToTarget(file,"photos")).isInstanceOf(AccessDeniedException.class));
        })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertThat(retained(file)).isFalse(); assertThat(relationCount(parent)).isZero(); assertThat(evidence(parent)).isEqualTo("before");
    }
    @Test void concurrentIdenticalAppendsCommitExactlyOneRelation() throws Exception {
        String parent=parent(), file=upload(TENANT,USER,"success"); var start=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var tasks=new ArrayList<Future<?>>();
            for(int i=0;i<2;i++) tasks.add(pool.submit(()->{await(start); command(parent,a->a.appendAndRetainExistingToTarget(file,"photos"));}));
            for(var task:tasks) task.get(30,TimeUnit.SECONDS);
        }
        assertThat(relationCount(parent)).isEqualTo(1); assertThat(retained(file)).isTrue();
    }
    @Test void appendAndDeleteHaveOneWinnerAndTheLoserLeavesNoPartialEffects() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            for(int i=0;i<6;i++) {
                String parent=parent(), file=upload(TENANT,USER,"success"); var start=new CyclicBarrier(2);
                var appended=pool.submit(()->{await(start); try {command(parent,a->a.appendAndRetainExistingToTarget(file,"photos")); return true;} catch(AccessDeniedException deletedFirst){return false;}});
                var deleted=pool.submit(()->{actor(); try {await(start); return files.deleteFile(file,USER);} catch(com.auraboot.framework.exception.BusinessException retainedFirst){assertThat(retainedFirst).hasMessageContaining("retention"); return false;} finally{MetaContext.clear();}});
                boolean a=appended.get(30,TimeUnit.SECONDS), d=deleted.get(30,TimeUnit.SECONDS);
                assertThat(a^d).as("append=%s delete=%s",a,d).isTrue();
                assertThat(retained(file)).isEqualTo(a); assertThat(relationCount(parent)).isEqualTo(a?1:0);
                assertThat(evidence(parent)).isEqualTo(a?"after":"before");
                assertThat(jdbc.queryForObject("SELECT deleted_flag FROM ab_file WHERE pid=?",Boolean.class,file)).isEqualTo(d);
            }
        }
    }
    @Test void deleteFirstRejectsAppendAndRollsBackParent() {
        String parent=parent(), file=upload(TENANT,USER,"success"); actor(); assertThat(files.deleteFile(file,USER)).isTrue();
        assertThatThrownBy(()->command(parent,a->a.appendAndRetainExistingToTarget(file,"photos"))).isInstanceOf(AccessDeniedException.class);
        assertThat(relationCount(parent)).isZero(); assertThat(retained(file)).isFalse(); assertThat(evidence(parent)).isEqualTo("before");
    }
    @Test void legacyRelationEntrypointsCannotReplaceOrRemoveACommandOwnedFileField() {
        String parent=parent(), first=upload(TENANT,USER,"success"), second=upload(TENANT,USER,"success");
        String projection="[\""+first+"\"]";
        command(parent,a->{data.update(model,parent,Map.of("photos",projection)); a.appendAndRetainExistingToTarget(first,"photos");});
        var before=jdbc.queryForMap("SELECT photos,evidence,row_version FROM "+table+" WHERE pid=?",parent);
        actor();
        var request=new com.auraboot.framework.file.dto.FileRelationRequestDTO();
        request.setEntityType(model); request.setEntityId(parent); request.setFieldName("photos"); request.setFileIds(new String[]{second});
        var old=new com.auraboot.framework.plugin.pf4j.FileAccessorImpl(files,storage,USER);
        // A broad parent UPDATE permit is deliberately present, but no exact field writer.
        MetaContext.runWithCommandPermitScope("ALL",()->{
            request.setFileIds(new String[0]);
            assertThatThrownBy(()->files.createFileRelation(request,USER)).hasMessageContaining("FIELD_WRITER_DENIED");
            request.setFileIds(new String[]{second});
            assertThatThrownBy(()->files.createFileRelation(request,USER)).hasMessageContaining("FIELD_WRITER_DENIED");
            assertThatThrownBy(()->files.removeFileRelation(model,parent,"photos")).hasMessageContaining("FIELD_WRITER_DENIED");
            assertThatThrownBy(()->files.appendFileRelation(request,USER)).hasMessageContaining("FIELD_WRITER_DENIED");
            assertThatThrownBy(()->old.linkExisting(second,model,parent,"photos")).hasMessageContaining("FIELD_WRITER_DENIED");
            assertThatThrownBy(()->old.linkExisting(first,model,parent,"photos")).hasMessageContaining("FIELD_WRITER_DENIED");
        });
        assertThat(relationCount(parent)).isEqualTo(1); assertThat(retained(first)).isTrue(); assertThat(retained(second)).isFalse();
        assertThat(jdbc.queryForMap("SELECT photos,evidence,row_version FROM "+table+" WHERE pid=?",parent)).isEqualTo(before);
        command(parent,a->{data.update(model,parent,Map.of("photos","[\""+first+"\",\""+second+"\"]"));
            a.appendAndRetainExistingToTarget(second,"photos");});
        assertThat(relationCount(parent)).isEqualTo(2); assertThat(retained(second)).isTrue();
    }
    @Test void ordinaryLegacyFileFieldStillReplacesItsRelations() {
        String parent=parent(), first=upload(TENANT,USER,"success"), second=upload(TENANT,USER,"success"); actor();
        var request=new com.auraboot.framework.file.dto.FileRelationRequestDTO();
        request.setEntityType(model); request.setEntityId(parent); request.setFieldName("legacy_photos");
        MetaContext.runWithCommandPermitScope("ALL",()->{
            request.setFileIds(new String[]{first}); assertThat(files.createFileRelation(request,USER)).isTrue();
            request.setFileIds(new String[]{second}); assertThat(files.createFileRelation(request,USER)).isTrue();
        });
        assertThat(files.getFilesByEntityAndField(model,parent,"legacy_photos")).extracting(com.auraboot.framework.file.entity.FileEntity::getPid)
                .containsExactly(second);
        assertThat(retained(first)).isFalse(); assertThat(retained(second)).isFalse(); assertThat(evidence(parent)).isEqualTo("before");
    }
    @Test void anExactCommandWriterStillCannotMutateAnImmutableFileFieldThroughLegacyApi() {
        String parent=parent(), file=upload(TENANT,USER,"success");
        var request=new com.auraboot.framework.file.dto.FileRelationRequestDTO();
        request.setEntityType(model); request.setEntityId(parent); request.setFieldName("frozen_photos"); request.setFileIds(new String[]{file});
        assertThatThrownBy(()->command(parent,a->files.createFileRelation(request,USER))).isInstanceOf(AccessDeniedException.class);
        actor(); assertThat(files.getFilesByEntityAndField(model,parent,"frozen_photos")).isEmpty();
        assertThat(retained(file)).isFalse(); assertThat(evidence(parent)).isEqualTo("before");
    }
    @Test void officialMultipartUploadIsAnOwnedUnboundStageUntilTheOriginalCommandAppendsIt() throws Exception {
        actor();
        org.mockito.Mockito.when(storage.type()).thenReturn(com.auraboot.framework.file.constant.StorageType.LOCAL);
        org.mockito.Mockito.when(storage.upload(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString())).thenReturn("staged-object");
        byte[] bytes="%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\n%%EOF".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var uploaded=files.uploadFile(new org.springframework.mock.web.MockMultipartFile("file","repair.pdf","application/pdf",bytes),USER);
        String file=uploaded.getFileId();
        assertThat(jdbc.queryForMap("SELECT tenant_id,created_by,status,retention_locked FROM ab_file WHERE pid=?",file))
                .containsEntry("tenant_id",TENANT).containsEntry("created_by",USER).containsEntry("status","success").containsEntry("retention_locked",false);
        assertThat(files.getFileRelations(file)).isEmpty();
        String parent=parent(); command(parent,a->a.appendAndRetainExistingToTarget(file,"photos"));
        assertThat(retained(file)).isTrue(); assertThat(relationCount(parent)).isEqualTo(1);
    }
    private static void await(CyclicBarrier start) { try {start.await(10,TimeUnit.SECONDS);} catch(Exception e){throw new AssertionError(e);} }
}
