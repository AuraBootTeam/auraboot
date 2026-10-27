package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.file.dao.mapper.FileMapper;
import com.auraboot.framework.file.dao.mapper.FileRelationMapper;
import com.auraboot.framework.file.entity.FileEntity;
import com.auraboot.framework.file.entity.FileRelationEntity;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.infrastructure.storage.StorageProvider;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.impl.pipeline.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Hermetic adversarial checks; physical transaction/race proofs live in the PostgreSQL IT. */
class CommandTargetFileAccessorFactoryTest {
    private final FileService files = mock(FileService.class);
    private final StorageProvider storage = mock(StorageProvider.class);
    private final FileMapper fileMapper = mock(FileMapper.class);
    private final FileRelationMapper relations = mock(FileRelationMapper.class);
    private final SqlSessionFactory sessions = mock(SqlSessionFactory.class);
    private final MetaModelService models = mock(MetaModelService.class);
    private final DynamicDataService data = mock(DynamicDataService.class);
    private final DynamicDataMapper records = mock(DynamicDataMapper.class);
    private final DataSource source = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final CommandTargetFileAccessorFactory factory = new CommandTargetFileAccessorFactory(
            files, storage, fileMapper, relations, sessions, models, data, records);
    private ModelDefinition model;
    private FieldDefinition field;
    private FileEntity file;
    private CommandPipelineContext ctx;

    @BeforeEach void setup() throws Exception {
        MetaContext.setContext(1L, 42L, "user-pid", "user"); MetaContext.setMemberId(7L);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        var holder = new ConnectionHolder(connection); holder.setSynchronizedWithTransaction(true);
        TransactionSynchronizationManager.bindResource(source, holder);
        Configuration config = new Configuration(new Environment("test", new JdbcTransactionFactory(), source));
        when(sessions.getConfiguration()).thenReturn(config);
        field = FieldDefinition.builder().code("photos").dataType("file")
                .allowedWriterCommands(List.of("order:repair")).extraProps(Map.of("multiple",true,"maxCount",10)).build();
        model = ModelDefinition.builder().code("order").status("PUBLISHED").tableName("mt_order").fields(List.of(field)).build();
        when(models.getModelDefinition("order")).thenAnswer(i -> Optional.of(model));
        when(records.selectRecordWriterTargetsForUpdate(anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(List.of(Map.of("pid","order-pid")));
        when(data.getById("order","order-pid")).thenReturn(Map.of("tenant_id",1L,"pid","order-pid","row_version",2L));
        file = new FileEntity(); file.setId(99L); file.setPid("file-pid");
        file.setCreatedBy(42L); file.setStatus("success"); file.setDeletedFlag(false);
        when(fileMapper.selectActiveByPidForUpdate(1L,"file-pid")).thenReturn(file);
        when(relations.selectList(any())).thenReturn(List.of());
        when(relations.insert(any(FileRelationEntity.class))).thenReturn(1);
        when(files.lockRetention("file-pid")).thenReturn(true);
        var command = new CommandDefinition(); command.setCode("order:repair"); command.setModelCode("order");
        ctx = CommandPipelineContext.builder().commandCode("order:repair").tenantId(1L).userId(42L)
                .request(new CommandExecuteRequest()).command(command).build();
        ctx.setAuthorizationVerdict(CommandAuthorizationVerdict.authorized("order.repair"));
        ctx.setTargetRecordReadable(true);
        ctx.setPermitPlan(new CommandPermitPlan(CommandPermitPlan.Decision.PERMIT,null,null,null,CommandPermitPlan.ScopeGrade.TARGET,2L));
    }
    @Test void onlyAttestedSingletonCreationCanCompleteAnImmutableSingleFileField() {
        field = FieldDefinition.builder().code("proof").columnName("proof").dataType("file").immutable(true)
                .allowedWriterCommands(List.of("order:repair")).build();
        model = ModelDefinition.builder().code("child").status("published").tableName("mt_child").immutable(true)
                .fields(List.of(field)).extension(Map.of("commandWriters",Map.of("create",List.of("order:repair"),
                        "update",List.of("other:update"),"delete",List.of("other:delete")))).build();
        when(models.getModelDefinition("child")).thenReturn(Optional.of(model));
        var row=Map.<String,Object>of("pid","new-child","tenant_id",1L,"created_by",42L,"row_version",1L,"proof","file-pid");
        when(data.getById("child","new-child")).thenReturn(row);
        when(fileMapper.currentPhysicalTransactionIdentity()).thenReturn("physical-tx");
        command(()->{try(var scope=factory.forCommand(ctx)) {
            var delegate=mock(DynamicDataService.class);when(delegate.create(eq("child"),anyMap())).thenReturn(row);
            new DynamicDataAccessorImpl(delegate).create("child",Map.of("proof","file-pid"));
            scope.accessor().linkAndRetainExistingToCreatedRecord("file-pid","child","new-child","proof");
        }return null;});
        verify(files).lockRetention("file-pid");verify(relations).insert(any(FileRelationEntity.class));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"physicalTx","rollbackOnly","closedConnection","readOnlyConnection","autoCommit","closedScope","thread","actor","tenant","nested","otherCommand"})
    void createdReceiptCannotOutliveItsOriginalAuthority(String invalidation) throws Exception {
        when(fileMapper.currentPhysicalTransactionIdentity()).thenReturn("physical-tx");
        var row=Map.<String,Object>of("pid","new-child","tenant_id",1L,"created_by",42L,"row_version",1L,"proof","file-pid");
        command(()->{try(var scope=factory.forCommand(ctx)) {
            var delegate=mock(DynamicDataService.class);when(delegate.create(eq("child"),anyMap())).thenReturn(row);
            assertThat(new DynamicDataAccessorImpl(delegate).tryCreate("child",Map.of("proof","file-pid"))).contains(row);
            Runnable link=()->scope.accessor().linkAndRetainExistingToCreatedRecord("file-pid","child","new-child","proof");
            try {
                switch(invalidation) {
                    case "physicalTx" -> when(fileMapper.currentPhysicalTransactionIdentity()).thenReturn("replacement-tx");
                    case "rollbackOnly" -> ((ConnectionHolder)TransactionSynchronizationManager.getResource(source)).setRollbackOnly();
                    case "closedConnection" -> when(connection.isClosed()).thenReturn(true);
                    case "readOnlyConnection" -> when(connection.isReadOnly()).thenReturn(true);
                    case "autoCommit" -> when(connection.getAutoCommit()).thenReturn(true);
                    case "closedScope" -> scope.close();
                    case "actor" -> MetaContext.setMemberId(999L);
                    case "tenant" -> {MetaContext.setContext(2L,42L,"user-pid","user");MetaContext.setMemberId(7L);}
                    case "nested" -> {MetaContext.runWithAuthorizedCommandCode("order:repair",()->assertThatThrownBy(link::run).isInstanceOf(AccessDeniedException.class));return null;}
                    case "otherCommand" -> {MetaContext.runWithAuthorizedCommandCode("order:other",()->assertThatThrownBy(link::run).isInstanceOf(AccessDeniedException.class));return null;}
                    case "thread" -> {try(var pool=Executors.newSingleThreadExecutor()) {assertThat(pool.submit(()->catchThrowable(link::run)).get()).isInstanceOf(AccessDeniedException.class);}return null;}
                    default -> throw new AssertionError(invalidation);
                }
            } catch(Exception error) {throw new AssertionError(error);}
            assertThatThrownBy(link::run).isInstanceOf(AccessDeniedException.class);
        }return null;});
        verify(files,never()).lockRetention(anyString());verify(relations,never()).insert(any(FileRelationEntity.class));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"stringField","multiple","virtual","wrongFieldWriter","wrongCreateWriter","changedProjection","changedVersion","occupiedSlot"})
    void creationDoesNotBypassFieldWriterProjectionOrSingleSlotPolicy(String invalidation) {
        field=FieldDefinition.builder().code("proof").columnName("proof").dataType("file").immutable(true)
                .allowedWriterCommands(List.of("order:repair")).build();
        model=ModelDefinition.builder().code("child").status("published").tableName("mt_child").immutable(true)
                .fields(List.of(field)).extension(Map.of("commandWriters",Map.of("create",List.of("order:repair"),
                        "update",List.of("other:update"),"delete",List.of("other:delete")))).build();
        when(models.getModelDefinition("child")).thenReturn(Optional.of(model));
        var row=new HashMap<String,Object>(Map.of("pid","new-child","tenant_id",1L,"created_by",42L,"row_version",1L,"proof","file-pid"));
        when(data.getById("child","new-child")).thenReturn(row);
        when(fileMapper.currentPhysicalTransactionIdentity()).thenReturn("physical-tx");
        command(()->{try(var scope=factory.forCommand(ctx)) {
            var delegate=mock(DynamicDataService.class);when(delegate.create(eq("child"),anyMap())).thenReturn(row);
            new DynamicDataAccessorImpl(delegate).create("child",Map.of("proof","file-pid"));
            switch(invalidation) {
                case "stringField" -> field.setDataType("string");
                case "multiple" -> field.setExtraProps(Map.of("multiple",true));
                case "virtual" -> field.setVirtual(true);
                case "wrongFieldWriter" -> field.setAllowedWriterCommands(List.of("other:write"));
                case "wrongCreateWriter" -> model.setExtension(Map.of("commandWriters",Map.of("create",List.of("other:create"),"update",List.of("other:update"),"delete",List.of("other:delete"))));
                case "changedProjection" -> row.put("proof","different-file");
                case "changedVersion" -> row.put("row_version",2L);
                case "occupiedSlot" -> when(relations.selectList(any())).thenReturn(List.of(new FileRelationEntity().setFileId("100")));
                default -> throw new AssertionError(invalidation);
            }
            assertThatThrownBy(()->scope.accessor().linkAndRetainExistingToCreatedRecord("file-pid","child","new-child","proof")).isInstanceOf(AccessDeniedException.class);
        }return null;});
        verify(files,never()).lockRetention(anyString());verify(relations,never()).insert(any(FileRelationEntity.class));
    }
    @Test void aPlausibleLookupDoesNotAttestCreation() {
        command(()->{try(var scope=factory.forCommand(ctx)) {
            assertThatThrownBy(()->scope.accessor().linkAndRetainExistingToCreatedRecord("file-pid","child","new-child","proof"))
                    .isInstanceOf(AccessDeniedException.class);
        }return null;});
        verify(files,never()).lockRetention(anyString());
    }
    @Test void creationOnlyNoTargetScopeStillCannotAppendToAParent() {
        ctx.setTargetRecordReadable(null);ctx.getRequest().setTargetRecordId(null);
        MetaContext.runWithCommandPermitPlan("ALL",null,"order",null,()->MetaContext.runWithAuthorizedCommandCode("order:repair",()->{
            try(var scope=factory.forCommand(ctx)) {
                assertThat(scope).isNotNull();
                assertThatThrownBy(()->scope.accessor().appendAndRetainExistingToTarget("file-pid","photos"))
                        .isInstanceOf(AccessDeniedException.class);
            }
        }));
    }
    @AfterEach void cleanup() {
        TransactionSynchronizationManager.unbindResourceIfPossible(source);
        TransactionSynchronizationManager.clear(); MetaContext.clear();
    }
    private <T> T command(Supplier<T> action) {
        return MetaContext.runWithCommandPermitPlan("TARGET",2L,"order","order-pid", () ->
                MetaContext.runWithAuthorizedCommandCode("order:repair", action));
    }
    private void append() { command(() -> { try(var scope=factory.forCommand(ctx)) {
        assertThat(scope).isNotNull(); scope.accessor().appendAndRetainExistingToTarget("file-pid","photos");
    } return null; }); }

    @Test void appendsExactRelationAfterParentLockAndRetentionWithoutCopying() {
        append();
        var order = inOrder(records, data, fileMapper, files, relations);
        order.verify(records).selectRecordWriterTargetsForUpdate(eq("mt_order"),eq("pid"),eq("attachment_target"),
                eq(Map.of("tenant_id",1L,"pid","order-pid")));
        order.verify(data).getById("order","order-pid");
        order.verify(fileMapper).selectActiveByPidForUpdate(1L,"file-pid");
        order.verify(relations).selectList(any()); order.verify(files).lockRetention("file-pid");
        order.verify(relations).insert(argThat((FileRelationEntity r) -> r.getTenantId()==1L
                && r.getEntityType().equals("order") && r.getEntityId().equals("order-pid")
                && r.getFieldName().equals("photos") && r.getFileId().equals("99") && r.getSortOrder()==0));
        verifyNoInteractions(storage); verify(files,never()).createFileRelation(any(),any());
    }
    @Test void exactRepeatRetainsButNeverReplacesOrDuplicates() {
        when(relations.selectList(any())).thenReturn(List.of(new FileRelationEntity().setFileId("99").setSortOrder(4)));
        append(); verify(files).lockRetention("file-pid"); verify(relations,never()).insert(any(FileRelationEntity.class));
    }
    @Test void keepsPriorFilesAndAppendsAfterHighestSortOrder() {
        when(relations.selectList(any())).thenReturn(List.of(new FileRelationEntity().setFileId("88").setSortOrder(6)));
        append(); verify(relations).insert(argThat((FileRelationEntity r)->r.getSortOrder()==7));
    }
    @Test void rejectsUnknownSingleVirtualAndWrongWriterFieldsBeforeAnyFileLock() {
        field.setCode("different"); assertThatThrownBy(this::append).isInstanceOf(AccessDeniedException.class);
        field.setCode("photos"); field.setExtraProps(Map.of("multiple",false));
        assertThatThrownBy(this::append).isInstanceOf(AccessDeniedException.class);
        field.setExtraProps(Map.of("multiple",true)); field.setVirtualType("computed_readonly");
        assertThatThrownBy(this::append).isInstanceOf(AccessDeniedException.class);
        field.setVirtualType(null); field.setAllowedWriterCommands(List.of("order:other"));
        assertThatThrownBy(this::append).hasMessageContaining("FIELD_WRITER_DENIED");
        verifyNoInteractions(fileMapper);
    }
    @Test void rejectsUnpublishedAndChildModelBeforeAnyFileLock() {
        model.setStatus("DRAFT"); assertThatThrownBy(this::append).isInstanceOf(AccessDeniedException.class);
        model.setStatus("PUBLISHED"); model.setExtension(Map.of("parentModel","other"));
        assertThatThrownBy(this::append).isInstanceOf(AccessDeniedException.class); verifyNoInteractions(fileMapper);
    }
    @Test void rejectsForeignOwnerUnfinalizedDeletedAndNonPublicFileBeforeRetention() {
        file.setCreatedBy(5L); assertThatThrownBy(this::append).isInstanceOf(AccessDeniedException.class);
        file.setCreatedBy(42L);
        file.setStatus("uploading"); assertThatThrownBy(this::append).isInstanceOf(AccessDeniedException.class);
        file.setStatus("success"); file.setDeletedFlag(true); assertThatThrownBy(this::append).isInstanceOf(AccessDeniedException.class);
        file.setDeletedFlag(false); file.setPid("different"); assertThatThrownBy(this::append).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(files); verify(relations,never()).insert(any(FileRelationEntity.class));
    }
    @Test void deniesParentWithoutVisibilityBeforeLockingFile() {
        when(data.getById("order","order-pid")).thenThrow(new AccessDeniedException("denied"));
        assertThatThrownBy(this::append).isInstanceOf(AccessDeniedException.class); verifyNoInteractions(fileMapper,files);
    }
    @Test void fieldCapacityFailureDoesNotRetainOrInsert() {
        field.setExtraProps(Map.of("multiple",true,"maxCount",1));
        when(relations.selectList(any())).thenReturn(List.of(new FileRelationEntity().setFileId("88")));
        assertThatThrownBy(this::append).hasMessageContaining("maximum count"); verifyNoInteractions(files);
    }
    @Test void requiresSuccessfulRetentionBeforeInserting() {
        when(files.lockRetention("file-pid")).thenReturn(false);
        assertThatThrownBy(this::append).hasMessageContaining("retention"); verify(relations,never()).insert(any(FileRelationEntity.class));
    }
    @Test void closesAtHandlerReturnAndDeniesTargetCommandActorAndTransactionReplacement() {
        command(() -> {
            var scope=factory.forCommand(ctx); var accessor=scope.accessor();
            MetaContext.runWithCommandPermitPlan("TARGET",2L,"order","another", () ->
                    assertThatThrownBy(()->accessor.appendAndRetainExistingToTarget("file-pid","photos")).isInstanceOf(AccessDeniedException.class));
            MetaContext.runWithAuthorizedCommandCode("order:other", () ->
                    assertThatThrownBy(()->accessor.appendAndRetainExistingToTarget("file-pid","photos")).isInstanceOf(AccessDeniedException.class));
            MetaContext.setMemberId(8L);
            assertThatThrownBy(()->accessor.appendAndRetainExistingToTarget("file-pid","photos")).isInstanceOf(AccessDeniedException.class);
            MetaContext.setMemberId(7L);
            Object original=TransactionSynchronizationManager.unbindResource(source);
            TransactionSynchronizationManager.bindResource(source,new ConnectionHolder(mock(Connection.class)));
            assertThatThrownBy(()->accessor.appendAndRetainExistingToTarget("file-pid","photos")).isInstanceOf(AccessDeniedException.class);
            TransactionSynchronizationManager.unbindResource(source); TransactionSynchronizationManager.bindResource(source,original);
            scope.close(); assertThatThrownBy(()->accessor.appendAndRetainExistingToTarget("file-pid","photos")).isInstanceOf(AccessDeniedException.class);
            return null;
        }); verifyNoInteractions(fileMapper,files);
    }
    @Test void crossThreadCannotUseAnOtherwiseActiveCapability() {
        command(() -> {
            try(var scope=factory.forCommand(ctx); var pool=Executors.newSingleThreadExecutor()) {
                assertThat(pool.submit(()->catchThrowable(()->scope.accessor().appendAndRetainExistingToTarget("file-pid","photos"))).get())
                        .isInstanceOf(AccessDeniedException.class);
            } catch(Exception error) { throw new AssertionError(error); } return null;
        }); verifyNoInteractions(fileMapper,files);
    }
    @Test void absentHumanAuthorityDryRunOrNoWritableTransactionDoesNotInstall() {
        assertThat(factory.forCommand(ctx)).isNull();
        command(()-> {ctx.getRequest().setDryRun(true); assertThat(factory.forCommand(ctx)).isNull();
            ctx.getRequest().setDryRun(false); ctx.setAuthorizationVerdict(null); assertThat(factory.forCommand(ctx)).isNull();
            ctx.setAuthorizationVerdict(CommandAuthorizationVerdict.authorized("order.repair"));
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(true); assertThat(factory.forCommand(ctx)).isNull(); return null;});
    }
    @Test void nestedCommandsCannotInstallAnotherTargetCapability() {
        command(() -> {try(var scope=factory.forCommand(ctx)) {
            assertThat(scope).isNotNull();
            assertThat(factory.forCommand(ctx)).isNull();
            MetaContext.runWithCommandPermitPlan("TARGET",2L,"order","other",()->assertThat(factory.forCommand(ctx)).isNull());
        } return null;});
    }
    @Test void staleParentVersionFailsBeforeAnyFileLock() {
        when(data.getById("order","order-pid")).thenReturn(Map.of("tenant_id",1L,"pid","order-pid","row_version",3L));
        assertThatThrownBy(this::append).hasMessageContaining("version_conflict"); verifyNoInteractions(fileMapper,files);
    }
    @Test void distinctFileLocksMustFollowAscendingPublicPidOrder() {
        var higher = new FileEntity(); higher.setId(100L); higher.setPid("z-file");
        higher.setCreatedBy(42L); higher.setStatus("success");
        when(fileMapper.selectActiveByPidForUpdate(1L,"z-file")).thenReturn(higher);
        when(files.lockRetention("z-file")).thenReturn(true);
        command(() -> {try(var scope=factory.forCommand(ctx)) {
            scope.accessor().appendAndRetainExistingToTarget("z-file","photos");
            assertThatThrownBy(()->scope.accessor().appendAndRetainExistingToTarget("file-pid","photos"))
                    .hasMessageContaining("ascending order");
        } return null;});
        verify(fileMapper,never()).selectActiveByPidForUpdate(1L,"file-pid");
    }
    @Test void impersonationCannotBorrowTheEffectiveUsersUploadOwnership() {
        command(() -> {
            MetaContext.setSessionContext(null,null,"tenant",null,null,"authenticated",1L,true,99L,"web");
            assertThat(factory.forCommand(ctx)).isNull(); return null;
        }); verifyNoInteractions(fileMapper,files);
    }
    @Test void nestedCommandBeforeOuterCapabilityInstallationCannotAcquireOne() {
        command(() -> {
            MetaContext.runWithAuthorizedCommandCode("order:repair",()->assertThat(factory.forCommand(ctx)).isNull());
            try(var scope=factory.forCommand(ctx)) { assertThat(scope).isNotNull(); }
            return null;
        });
    }
    @Test void claimedAsyncTaskCannotInstallTheCapabilityWithRestoredHumanIdentity() {
        command(() -> {
            var snapshot=new com.auraboot.framework.meta.service.impl.AsyncTaskExecutionContext.Snapshot(1L,"task",1L,42L,"token");
            try(var ignored=(AutoCloseable)org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    com.auraboot.framework.meta.service.impl.AsyncTaskExecutionContext.class,"enter",snapshot)) {
                assertThat(factory.forCommand(ctx)).isNull();
            } catch(Exception error) {throw new AssertionError(error);}
            return null;
        });
    }
    @Test void legacyConstructorDoesNotGrantTargetCapability() {
        assertThatThrownBy(()->new FileAccessorImpl(files,storage,42L).appendAndRetainExistingToTarget("file-pid","photos"))
                .isInstanceOf(UnsupportedOperationException.class); verifyNoInteractions(files,fileMapper,relations,storage);
    }
}
