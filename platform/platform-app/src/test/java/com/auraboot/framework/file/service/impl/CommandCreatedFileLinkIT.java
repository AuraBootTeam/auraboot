package com.auraboot.framework.file.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.meta.service.impl.pipeline.*;
import com.auraboot.framework.plugin.extension.FileAccessor;
import com.auraboot.framework.plugin.pf4j.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** Actual CREATE -> original scoped file SPI -> retained relation on an immutable scalar file field. */
@SpringBootTest(classes=TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommandCreatedFileLinkIT {
    static final String COMMAND="creation:file",FIELD="proof_file";
    static final long TENANT=992720001L,USER=992720002L,MEMBER=992720003L;
    static final Path STORAGE=directory();
    static Path directory(){try{return Files.createTempDirectory("aura-created-files-slot72-");}catch(java.io.IOException e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("aura.storage.local.base-path",STORAGE::toString);}
    @Autowired MetaModelService models;@Autowired SchemaManagementService schema;@Autowired DynamicDataService data;
    @Autowired FileService files;@Autowired CommandTargetFileAccessorFactory factory;@Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    String rootModel,childModel,rootPid;String file;
    @BeforeAll void metadata() {
        actor();String suffix=UniqueIdGenerator.generate().toLowerCase();rootModel="cfroot_"+suffix;childModel="cfchild_"+suffix;
        models.saveDefinition(ModelDefinition.builder().code(rootModel).tableName("mt_"+rootModel).status("published").modelType("entity")
                .fields(List.of(FieldDefinition.builder().code("name").columnName("name").dataType("string").build())).build());
        models.saveDefinition(ModelDefinition.builder().code(childModel).tableName("mt_"+childModel).status("published").modelType("entity")
                .immutable(true).commandOnlyCreate(true).extension(Map.of("commandWriters",Map.of("create",List.of(COMMAND),
                        "update",List.of("other:update"),"delete",List.of("other:delete"))))
                .fields(List.of(FieldDefinition.builder().code(FIELD).columnName(FIELD).dataType("file").maxLength(128)
                        .immutable(true).allowedWriterCommands(List.of(COMMAND)).build())).build());
        assertTrue(schema.createTableByModel(rootModel).isSuccess());assertTrue(schema.createTableByModel(childModel).isSuccess());
        rootPid=MetaContext.runWithCommandPermitScope("ALL",()->data.create(rootModel,Map.of("name","root"))).get("pid").toString();
    }
    @BeforeEach void upload() {
        actor();file=files.uploadFile(new MockMultipartFile("file","evidence.txt","text/plain",new byte[]{1,2,3}),USER).getFileId();
        assertFalse(retained());
    }
    @AfterEach void clear(){MetaContext.clear();}
    void actor(){MetaContext.setContext(TENANT,USER,"created-file-actor","actor");MetaContext.setMemberId(MEMBER);}
    void command(boolean targeted,Consumer<FileAccessor> action) {
        new TransactionTemplate(transactions).executeWithoutResult(status->{
            String model=targeted?rootModel:childModel,target=targeted?rootPid:null;
            var definition=new CommandDefinition();definition.setCode(COMMAND);definition.setModelCode(model);
            var request=new CommandExecuteRequest();request.setTargetRecordId(target);
            var context=CommandPipelineContext.builder().commandCode(COMMAND).command(definition).request(request).tenantId(TENANT).userId(USER).build();
            context.setTargetRecordReadable(targeted?true:null);context.setAuthorizationVerdict(CommandAuthorizationVerdict.authorized("creation.file"));
            context.setPermitPlan(new CommandPermitPlan(CommandPermitPlan.Decision.PERMIT,null,null,target,CommandPermitPlan.ScopeGrade.ALL,null));
            MetaContext.runWithCommandPermitPlan("ALL",null,model,target,()->MetaContext.runWithAuthorizedCommandCode(COMMAND,()->{
                try(var scope=factory.forCommand(context)){assertNotNull(scope);action.accept(scope.accessor());}
            }));
        });
    }
    String create(){return new DynamicDataAccessorImpl(data).create(childModel,Map.of(FIELD,file)).get("pid").toString();}
    long count(String pid){return jdbc.queryForObject("SELECT count(*) FROM ab_file_relation WHERE tenant_id=? AND entity_type=? AND entity_id=? AND field_name=? AND deleted_flag=false",Long.class,TENANT,childModel,pid,FIELD);}
    boolean retained(){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT retention_locked FROM ab_file WHERE tenant_id=? AND pid=?",Boolean.class,TENANT,file));}
    @Test void immutableChildScalarProjectionGetsOneRetainedRelationWithoutAnUpdate() {
        var pid=new java.util.concurrent.atomic.AtomicReference<String>();
        command(true,access->{pid.set(create());access.linkAndRetainExistingToCreatedRecord(file,childModel,pid.get(),FIELD);
            access.linkAndRetainExistingToCreatedRecord(file,childModel,pid.get(),FIELD);});
        assertEquals(1,count(pid.get()));assertTrue(retained());
        assertEquals(file,jdbc.queryForObject("SELECT "+FIELD+" FROM mt_"+childModel+" WHERE tenant_id=? AND pid=?",String.class,TENANT,pid.get()));
        assertEquals(1L,jdbc.queryForObject("SELECT row_version FROM mt_"+childModel+" WHERE tenant_id=? AND pid=?",Long.class,TENANT,pid.get()));
        assertTrue(models.getModelDefinition(childModel).orElseThrow().isImmutable());
    }
    @Test void noTargetCreateCommandCanOnlyCompleteItsNewRecord() {
        var pid=new java.util.concurrent.atomic.AtomicReference<String>();
        command(false,access->{pid.set(create());access.linkAndRetainExistingToCreatedRecord(file,childModel,pid.get(),FIELD);});
        assertEquals(1,count(pid.get()));assertTrue(retained());
        assertThrows(RuntimeException.class,()->command(false,access->access.appendAndRetainExistingToTarget(file,FIELD)));
    }
    @Test void oldUpdateRelationApiStillRefusesTheImmutableChildAndRollsBackCreate() {
        var pid=new java.util.concurrent.atomic.AtomicReference<String>();
        assertThrows(RuntimeException.class,()->command(true,access->{pid.set(create());access.linkExisting(file,childModel,pid.get(),FIELD);}));
        assertEquals(0,count(pid.get()));assertFalse(retained());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM mt_"+childModel+" WHERE tenant_id=? AND pid=?",Integer.class,TENANT,pid.get()));
    }
    @Test void priorTransactionRecordWithSameActorFileAndProjectionIsNotCreationProof() {
        String pid=MetaContext.runWithCommandPermitScope("ALL",()->MetaContext.runWithAuthorizedCommandCode(COMMAND,this::create));
        assertThrows(RuntimeException.class,()->command(true,access->access.linkAndRetainExistingToCreatedRecord(file,childModel,pid,FIELD)));
        assertEquals(0,count(pid));assertFalse(retained());
    }
    @Test void originalTransactionFailureRollsBackRecordRelationAndRetention() {
        var pid=new java.util.concurrent.atomic.AtomicReference<String>();
        assertThrows(IllegalStateException.class,()->command(true,access->{pid.set(create());access.linkAndRetainExistingToCreatedRecord(file,childModel,pid.get(),FIELD);throw new IllegalStateException("domain failed");}));
        assertEquals(0,count(pid.get()));assertFalse(retained());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM mt_"+childModel+" WHERE tenant_id=? AND pid=?",Integer.class,TENANT,pid.get()));
    }
    @Test void wrongFileAndNestedCommandCannotClaimTheNewRecord() {
        assertThrows(RuntimeException.class,()->command(true,access->{String pid=create();access.linkAndRetainExistingToCreatedRecord("different-file",childModel,pid,FIELD);}));
        assertFalse(retained());
        assertThrows(RuntimeException.class,()->command(true,access->{String pid=MetaContext.runWithAuthorizedCommandCode(COMMAND,this::create);
            access.linkAndRetainExistingToCreatedRecord(file,childModel,pid,FIELD);}));
        assertFalse(retained());
    }
    @Test void nonOwnerFinalizedFileCannotBeAttachedByTheCreator() {
        jdbc.update("UPDATE ab_file SET created_by=? WHERE tenant_id=? AND pid=?",USER+1,TENANT,file);
        assertThrows(RuntimeException.class,()->command(true,access->{String pid=create();access.linkAndRetainExistingToCreatedRecord(file,childModel,pid,FIELD);}));
        assertFalse(retained());
    }
}
