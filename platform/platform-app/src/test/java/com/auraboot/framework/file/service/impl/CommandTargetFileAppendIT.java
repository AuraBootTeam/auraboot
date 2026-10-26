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
import org.springframework.boot.test.mock.mockito.MockBean;
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
class CommandTargetFileAppendIT {
    private static final long TENANT=991_940_001L, USER=991_940_002L;
    @Autowired CommandTargetFileAccessorFactory factory;
    @Autowired FileService files;
    @Autowired MetaModelService models;
    @Autowired SchemaManagementService schema;
    @Autowired DynamicDataService data;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockBean StorageProvider storage;
    private final AtomicLong seq=new AtomicLong(System.currentTimeMillis());
    private String model, table, command;

    @BeforeAll void publishFixture() {
        String database=System.getenv("POSTGRES_DB");
        assertThat(database).as("An owned aura runtime is required").startsWith("auraboot_");
        assertThat(jdbc.queryForObject("SELECT current_database()",String.class)).isEqualTo(database);
        actor();
        model="file_append_"+Long.toString(System.currentTimeMillis(),36); table="mt_"+model; command=model+":repair";
        var photos=FieldDefinition.builder().code("photos").columnName("photos").dataType("file").length(32768).maxLength(32768)
                .extraProps(Map.of("multiple",true,"maxCount",10)).allowedWriterCommands(List.of(command)).build();
        var evidence=FieldDefinition.builder().code("evidence").columnName("evidence").dataType("string").maxLength(128).build();
        models.saveDefinition(ModelDefinition.builder().code(model).tableName(table).status("published")
                .modelType("entity").displayName("Command target append IT").fields(List.of(photos,evidence)).build());
        // Explicit model initialization through the official schema service, never ad-hoc ALTER.
        var result=schema.createTableByModel(model);
        assertThat(result.isSuccess()).as(result.getErrorMessage()).isTrue();
        assertThat(models.getModelDefinition(model).orElseThrow().getFields()).anyMatch(f->f.getCode().equals("photos"));
        assertThat(jdbc.queryForObject("SELECT data_type FROM information_schema.columns WHERE table_name=? AND column_name='photos'",
                String.class,table)).isEqualTo("character varying");
        MetaContext.clear();
    }
    @AfterEach void clear() { MetaContext.clear(); }
    private void actor() { MetaContext.setContext(TENANT,USER,"file-append-user","file-append-user"); MetaContext.setMemberId(USER+1); }
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
            new TransactionTemplate(transactions).executeWithoutResult(status->{
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
        assertThat(jdbc.queryForObject("SELECT photos FROM "+table+" WHERE pid=?",String.class,parent)).isEqualTo(bothProjection);
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
        String parent=parent(), good=upload(TENANT,USER,"success"), foreign=upload(TENANT,USER+9,"success");
        assertThatThrownBy(()->command(parent,a->{a.appendAndRetainExistingToTarget(good,"photos");
            assertThatThrownBy(()->a.appendAndRetainExistingToTarget(foreign,"photos")).isInstanceOf(AccessDeniedException.class);}))
                .isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertThat(relationCount(parent)).isZero(); assertThat(retained(good)).isFalse(); assertThat(evidence(parent)).isEqualTo("before");
    }
    @Test void foreignTenantOwnerAndUnfinalizedUploadsLeaveNoWrites() {
        for(String file:List.of(upload(TENANT+1,USER,"success"),upload(TENANT,USER+1,"success"),upload(TENANT,USER,"uploading"))) {
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
    private static void await(CyclicBarrier start) { try {start.await(10,TimeUnit.SECONDS);} catch(Exception e){throw new AssertionError(e);} }
}
