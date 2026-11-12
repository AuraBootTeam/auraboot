package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.exception.MetaRecordNotFoundException;
import com.auraboot.framework.meta.exception.RecordVersionConflictException;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.meta.service.impl.pipeline.*;
import com.auraboot.framework.plugin.extension.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Hermetic owner decisions, native call parameters and physical capability lifetime. */
class BusinessCommandDataAccessorFactoryTest {
    final DynamicDataService data = mock(DynamicDataService.class);
    final MetaModelService models = mock(MetaModelService.class);
    final DataSource source = mock(DataSource.class);
    final Connection connection = mock(Connection.class);
    final com.auraboot.framework.meta.mapper.DynamicDataMapper mapper=mock(com.auraboot.framework.meta.mapper.DynamicDataMapper.class);
    final Owner owner = new Owner();
    BusinessCommandDataAccessorFactory factory;
    CommandPipelineContext context;
    ConnectionHolder holder;
    Map<String, Object> root = row("root-1", Map.of("status", "draft"));
    Map<String, Object> line = row("line-1", Map.of("parent", "root-1", "qty", 2, "secret_cost", 99));

    @BeforeEach void prepare() {
        MetaContext.setContext(7L, 20L, "actor", "Actor"); MetaContext.setMemberId(30L);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        holder = new ConnectionHolder(connection); holder.setSynchronizedWithTransaction(true);
        TransactionSynchronizationManager.bindResource(source, holder);
        var configuration = new Configuration();
        configuration.setEnvironment(new Environment("unit", mock(org.apache.ibatis.transaction.TransactionFactory.class), source));
        var sessions = mock(SqlSessionFactory.class); when(sessions.getConfiguration()).thenReturn(configuration);
        factory = new BusinessCommandDataAccessorFactory(data, models, sessions, mapper);
        var command = new CommandDefinition(); command.setCode("receipt.confirm"); command.setModelCode("receipt");
        var request = new CommandExecuteRequest(); request.setTargetRecordId("root-1");
        context = CommandPipelineContext.builder().commandCode("receipt.confirm").tenantId(7L).userId(20L)
                .command(command).request(request).targetRecordReadable(true).targetRecordVersion(9L)
                .businessTargetAuthorization(new CommandPipelineContext.BusinessTargetAuthorization(
                        "receipt.confirm", "receipt", "root-1", "update", 7L, 20L, 30L))
                .execConfig(Map.of("type","custom","permissions",List.of("inventory.receipt.confirm"),"handlerParams",Map.of("targetRecordAuthorization","business","targetRecordAction","update","dslPersistence",false)))
                .authorizationVerdict(CommandAuthorizationVerdict.authorized("inventory.receipt.confirm"))
                .permitPlan(new CommandPermitPlan(CommandPermitPlan.Decision.PERMIT, null, null, "root-1", CommandPermitPlan.ScopeGrade.TARGET, 9L))
                .build();
        for (String model : owner.businessResourceModels()) when(models.getModelDefinition(model))
                .thenReturn(Optional.of(ModelDefinition.builder().code(model).sourceType("physical").tableName("mt_"+model).modelType("entity").build()));
        when(mapper.selectTargetVersionForUpdate(anyString(),eq("pid"),eq(7L),anyString())).thenReturn(List.of(Map.of("row_version",9L)));
        when(data.getByIdForAuthorization("receipt", "root-1")).thenAnswer(call -> root);
        when(data.getByIdForAuthorization("line", "line-1")).thenAnswer(call -> line);
    }
    @AfterEach void clear() {
        if (TransactionSynchronizationManager.hasResource(source)) TransactionSynchronizationManager.unbindResource(source);
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.clear(); MetaContext.clear();
    }
    <T> T command(Function<BusinessCommandDataAccessorFactory.Scope,T> body) {
        return MetaContext.runWithCommandPermitPlan("TARGET", 9L, "receipt", "root-1", () ->
                MetaContext.runWithAuthorizedCommandCode("receipt.confirm", () -> {
                    try (var scope = factory.open(context, owner)) { return body.apply(scope); }
                }));
    }
    static Map<String, Object> row(String pid, Map<String, Object> values) {
        var row = new LinkedHashMap<String, Object>(values);
        row.put("pid", pid); row.put("tenant_id", 7L); row.put("row_version", 9L); return row;
    }
    static void denied(Runnable operation) { assertThatThrownBy(operation::run).isInstanceOf(DataAccessorException.class); }

    @Test void approvedReadsUseExactNativeTargetAndProjectFieldsWithoutChangingTheActor() {
        when(data.getByIdForAuthorization("line", "line-1")).thenAnswer(call -> {
            assertThat(MetaContext.getCurrentUserId()).isEqualTo(20L);
            assertThat(MetaContext.getCurrentMemberId()).isEqualTo(30L);
            assertThat(MetaContext.getCommandPermitScopeFor("line")).isEqualTo("TARGET");
            assertThat(MetaContext.getCommandPermitTargetFor("line")).isEqualTo("line-1");
            assertThat(MetaContext.getCommandPermitScopeFor("ledger")).isNull(); return line;
        });
        command(scope -> {
            var result = scope.accessor().getById("line", "line-1");
            assertThat(result).containsEntry("qty", 2).doesNotContainKey("secret_cost");
            assertThatThrownBy(() -> result.put("qty", 7)).isInstanceOf(UnsupportedOperationException.class);
            assertThat(MetaContext.getCommandPermitTargetFor("receipt")).isEqualTo("root-1"); return null;
        });
        assertThat(holder.isRollbackOnly()).isFalse();
    }
    @Test void undeclaredModelsAreDeniedBeforeAnyDatabaseLookup() {
        command(scope -> { denied(() -> scope.accessor().getByIdOrNull("foreign", "any")); return null; });
        verify(data, never()).getByIdForAuthorization(eq("foreign"), anyString());
        assertThat(holder.isRollbackOnly()).isTrue();
    }
    @Test void approvedInCandidatesUseOneBoundedNativeQueryAndStillProjectEachRow() {
        when(data.list(eq("line"),any())).thenAnswer(call->{
            DynamicQueryRequest request=call.getArgument(1);
            assertThat(request.getPageSize()).isEqualTo(2);
            assertThat(request.getConditions()).hasSize(1);
            assertThat(request.getConditions().getFirst().getOperator()).isEqualTo(QueryCondition.Operator.IN);
            assertThat(request.getConditions().getFirst().getValues()).containsExactly("root-1");
            return PaginationResult.of(List.of(line),1L,1,2);
        });
        command(scope->{
            var result=scope.accessor().queryIn("line","parent",List.of("root-1","root-1"));
            assertThat(result).hasSize(1);assertThat(result.getFirst()).containsEntry("qty",2).doesNotContainKey("secret_cost");return null;
        });
        verify(data,times(1)).list(eq("line"),any());assertThat(holder.isRollbackOnly()).isFalse();
    }
    @Test void oneUnapprovedInCandidateRejectsTheWholeBatchBeforeReading() {
        command(scope->{denied(()->scope.accessor().queryIn("line","parent",List.of("root-1","other-root")));return null;});
        verify(data,never()).list(anyString(),any());assertThat(holder.isRollbackOnly()).isTrue();
    }
    @Test void foreignParentAndForeignTenantAreRejectedAndPoisonTheOriginalTransaction() {
        line = row("line-1", Map.of("parent", "other-root", "qty", 2));
        command(scope -> { denied(() -> scope.accessor().getById("line", "line-1")); return null; });
        assertThat(holder.isRollbackOnly()).isTrue();
    }
    @Test void nativeTenantMismatchCannotBeApprovedByOwnerPolicy() {
        line = new HashMap<>(line); line.put("tenant_id", 8L);
        command(scope -> { denied(() -> scope.accessor().getById("line", "line-1")); return null; });
        assertThat(holder.isRollbackOnly()).isTrue();
    }
    @Test void queryApprovalAndEachReturnedRowAreBothRequired() {
        when(data.list(eq("line"), any())).thenReturn(new PaginationResult<>(List.of(line), 1L, 1, 2));
        command(scope -> {
            var rows = scope.accessor().query("line", Map.of("parent", "root-1"));
            assertThat(rows).hasSize(1); assertThat(rows.getFirst()).doesNotContainKey("secret_cost"); return null;
        });
        assertThat(holder.isRollbackOnly()).isFalse();
    }
    @Test void queryCannotLeakForeignRowsEvenWhenTheNativeAdapterIsBroken() {
        when(data.list(eq("line"), any())).thenReturn(new PaginationResult<>(List.of(row("line-2", Map.of("parent", "other-root"))), 1L, 1, 2));
        command(scope -> { denied(() -> scope.accessor().query("line", Map.of("parent", "root-1"))); return null; });
        assertThat(holder.isRollbackOnly()).isTrue();
    }
    @Test void oversizedQueryFailsRatherThanSilentlyReturningPartialData() {
        when(data.list(eq("line"), any())).thenReturn(new PaginationResult<>(List.of(line), 3L, 1, 2));
        command(scope -> { denied(() -> scope.accessor().query("line", Map.of("parent", "root-1"))); return null; });
    }
    @Test void unboundedQueriesAreRejectedBeforeNativeExecution() {
        command(scope -> { denied(() -> scope.accessor().query("line", Map.of())); return null; });
        verify(data, never()).list(anyString(), any());
    }
    @Test void approvedUpdatePinsTheHostLoadedVersionAndLimitsFields() {
        when(data.update(eq("line"), eq("line-1"), anyMap())).thenAnswer(call -> {
            assertThat(MetaContext.getCommandExpectedVersion("line", "line-1")).isEqualTo(9L);
            return row("line-1", Map.of("parent", "root-1", "qty", 3, "secret_cost", 99));
        });
        command(scope -> {
            assertThat(scope.accessor().update("line", "line-1", Map.of("qty", 3)))
                    .containsEntry("qty", 3).doesNotContainKey("secret_cost"); return null;
        });
    }
    @Test void unauthorizedFieldCannotCommitEarlierAllowedWorkEvenIfHandlerCatchesIt() {
        when(data.create(eq("ledger"), anyMap())).thenReturn(row("ledger-1", Map.of("parent", "root-1", "qty", 3)));
        command(scope -> {
            scope.accessor().create("ledger", Map.of("parent", "root-1", "qty", 3));
            denied(() -> scope.accessor().update("line", "line-1", Map.of("secret_cost", 100)));
            denied(() -> scope.accessor().getById("line", "line-1")); return null;
        });
        assertThat(holder.isRollbackOnly()).isTrue(); verify(data, never()).update(anyString(), anyString(), anyMap());
    }
    @Test void systemFieldsAreRejectedEvenWhenTheOwnerAccidentallyListsThem() {
        owner.extraWrite = "tenant_id";
        command(scope -> { denied(() -> scope.accessor().create("ledger", Map.of("parent", "root-1", "qty", 3, "tenant_id", 7L))); return null; });
        verify(data, never()).create(anyString(), anyMap());
    }
    @Test void staleClientVersionCannotReplaceTheHostVersion() {
        command(scope -> {
            assertThatThrownBy(() -> scope.accessor().update("line", "line-1", Map.of("qty", 3, "_expectedVersion", 8)))
                    .isInstanceOf(RecordVersionConflictException.class); return null;
        });
        verify(data, never()).update(anyString(), anyString(), anyMap());
    }
    @Test void capturedPolicyReadsExpireImmediatelyAfterTheCallback() {
        command(scope -> {
            scope.accessor().getById("line", "line-1");
            denied(() -> owner.captured.getById("line", "line-1")); return null;
        });
        assertThat(holder.isRollbackOnly()).isTrue();
    }
    @Test void closingScopeRevokesCapturedDataAccess() {
        command(scope -> { var captured = scope.accessor(); scope.close(); denied(() -> captured.getById("line", "line-1")); return null; });
        assertThat(holder.isRollbackOnly()).isTrue();
    }
    @Test void aDifferentPhysicalTransactionCannotReuseTheCapability() {
        command(scope -> {
            TransactionSynchronizationManager.unbindResource(source);
            var other = new ConnectionHolder(mock(Connection.class)); other.setSynchronizedWithTransaction(true);
            TransactionSynchronizationManager.bindResource(source, other);
            try {
                denied(() -> scope.accessor().getById("line", "line-1"));
                assertThat(other.isRollbackOnly()).isFalse(); assertThat(holder.isRollbackOnly()).isTrue();
            } finally {
                TransactionSynchronizationManager.unbindResource(source); TransactionSynchronizationManager.bindResource(source, holder);
            }
            return null;
        });
    }
    @Test void capabilityCannotCrossThreads() {
        command(scope -> {
            try (var executor = Executors.newSingleThreadExecutor()) {
                executor.submit(() -> denied(() -> scope.accessor().getById("line", "line-1"))).get();
            } catch (Exception failure) { throw new AssertionError(failure); }
            return null;
        });
        assertThat(holder.isRollbackOnly()).isTrue();
    }
    @Test void rootVersionIsRecheckedWhenTheResourceFrameStarts() {
        root = row("root-1", Map.of("status", "draft")); root.put("row_version", 10L);
        assertThatThrownBy(() -> command(scope -> null)).isInstanceOf(RecordVersionConflictException.class);
        assertThat(holder.isRollbackOnly()).isTrue();
    }
    @Test void businessResultCannotReturnAnInternalRecordOrNewSensitiveField() {
        command(scope -> { denied(() -> scope.projectResult(Map.of("status", "confirmed", "secret_cost", 99))); return null; });
        assertThat(holder.isRollbackOnly()).isTrue();
    }
    @Test void supportedResultEnvelopeRemainsImmutable() {
        command(scope -> { assertThat(scope.projectResult(Map.of("status", "confirmed"))).containsEntry("status", "confirmed"); return null; });
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void structuredOwnerCannotRunWithoutTheHostProjectionOrWeakenItInTheInvocation(boolean weaken) {
        owner.resultProjection=Map.of("status",BusinessResultField.rootJsonPath("status","dryRun","quality"));
        if(weaken) {
            var invocation=mock(BusinessResourceAuthorization.class,org.mockito.AdditionalAnswers.delegatesTo(owner));
            when(invocation.businessResultProjection()).thenReturn(Map.of("status",BusinessResultField.rootField("status")));
            owner.invocation=invocation;
        }
        assertThatThrownBy(() -> command(scope -> null)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verify(data,never()).getByIdForAuthorization(anyString(),anyString());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"path","visibility","grant"})
    void structuredSourcePolicyCannotChangeInsideTheResourceFrame(String change) {
        owner.resultProjection=Map.of("status",BusinessResultField.rootJsonPath("status","dryRun","quality"));
        var grant=new com.auraboot.framework.permission.engine.model.BusinessGrantDecision(
                com.auraboot.framework.permission.engine.model.PermissionResult.allow(List.of()),7L,20L,30L,"receipt","root-1",
                List.of(new com.auraboot.framework.permission.engine.model.BusinessGrantDecision.Grant(1L,2L,"inventory.receipt.confirm",
                        new com.auraboot.framework.permission.engine.model.BusinessGrantDecision.Fields(false,Set.of("status"),Set.of()))));
        context.setBusinessGrantDecision(grant);
        context.setBusinessResultProjection(new com.auraboot.framework.meta.service.impl.BusinessResultProjectionPlan(
                context.getBusinessTargetAuthorization(),grant,owner.resultProjection,Map.of("status","status"),Set.of("status"),List.of(),List.of()));
        command(scope -> {
            if("path".equals(change)) owner.resultProjection=Map.of("status",BusinessResultField.rootJsonPath("status","dryRun","samples"));
            else if("grant".equals(change)) context.setBusinessGrantDecision(null);
            else context.setBusinessResultProjection(new com.auraboot.framework.meta.service.impl.BusinessResultProjectionPlan(
                    context.getBusinessTargetAuthorization(),grant,owner.resultProjection,Map.of("status","status"),Set.of("status","private"),List.of(),List.of()));
            denied(() -> scope.accessor().getById("line","line-1"));return null;
        });
        verify(data,never()).getByIdForAuthorization("line","line-1");assertThat(holder.isRollbackOnly()).isTrue();
    }
    @Test void hostRootCannotBeReplacedByAnotherRecordOrCreateOperation() {
        command(scope -> { denied(() -> scope.accessor().update("receipt", "another-root", Map.of("status", "confirmed"))); return null; });
        verify(data, never()).getByIdForAuthorization("receipt", "another-root");
    }
    @Test void unknownFieldSnapshotsCannotMutateHostFacts() {
        var nested = new HashMap<String, Object>(); nested.put("qty", 1);
        var request = new BusinessResourceAuthorization.Request(BusinessResourceAuthorization.Action.UPDATE,
                "line", "line-1", Map.of("nested", nested), Map.of("qty", 3), null);
        nested.put("qty", 99);
        assertThat(((Map<?, ?>) request.record().get("nested")).get("qty")).isEqualTo(1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"tenant","user","member","actor","command","target","version","witness","plan","read-only","no-transaction","synchronization"})
    void actorProofOrTransactionChangesRevokeTheOriginalResourceFrame(String mode) {
        command(scope -> {
            switch(mode) {
                case "tenant" -> MetaContext.setContext(8L,20L,"actor","Wrong tenant");
                case "user" -> MetaContext.setContext(7L,21L,"actor","Wrong user");
                case "member" -> MetaContext.setMemberId(31L);
                case "actor" -> MetaContext.setContext(7L,20L,"other-actor","Wrong actor");
                case "command" -> context.getCommand().setCode("another.command");
                case "target" -> context.getRequest().setTargetRecordId("another-root");
                case "version" -> context.setTargetRecordVersion(10L);
                case "witness" -> context.setBusinessTargetAuthorization(new CommandPipelineContext.BusinessTargetAuthorization(
                        "receipt.confirm","receipt","root-1","update",7L,20L,30L));
                case "plan" -> context.setPermitPlan(new CommandPermitPlan(CommandPermitPlan.Decision.PERMIT,null,null,"root-1",CommandPermitPlan.ScopeGrade.TARGET,9L));
                case "read-only" -> TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
                case "no-transaction" -> TransactionSynchronizationManager.setActualTransactionActive(false);
                case "synchronization" -> {TransactionSynchronizationManager.clearSynchronization();TransactionSynchronizationManager.initSynchronization();}
                default -> throw new AssertionError(mode);
            }
            denied(() -> scope.accessor().getById("line","line-1"));return null;
        });
        assertThat(holder.isRollbackOnly()).isTrue();
        verify(data,never()).getByIdForAuthorization("line","line-1");
    }

    @Test void lockedOwnerFactsCannotApproveAnOutdatedSourceVersion() {
        owner.inspectLockedLine = true;
        when(mapper.selectTargetVersionForUpdate("mt_line", "pid", 7L, "line-1"))
                .thenReturn(List.of(Map.of("row_version", 10L)));
        command(scope -> { denied(() -> scope.accessor().getById("line", "line-1")); return null; });
        assertThat(holder.isRollbackOnly()).isTrue();
    }

    @Test void lockedOwnerFactsUseTheSameTransactionAndExpireAfterCallback() {
        owner.inspectLockedLine = true;
        command(scope -> {
            assertThat(scope.accessor().getById("line", "line-1")).containsEntry("qty", 2);
            verify(mapper).selectTargetVersionForUpdate("mt_line", "pid", 7L, "line-1");
            denied(() -> ((BusinessResourceSourceReader) owner.captured).getLockedById("line", "line-1"));
            return null;
        });
        assertThat(holder.isRollbackOnly()).isTrue();
    }

    @Test void optionalMissingModelRequiresOwnerApprovalAndCannotReadNatively() {
        owner.optionalReference = true;
        when(models.getModelDefinition("reference")).thenReturn(Optional.empty());
        command(scope -> {
            assertThat(scope.accessor().getByIdOrNull("reference", "optional-1")).isNull();
            return null;
        });
        verify(data, never()).getByIdForAuthorization(eq("reference"), anyString());
        assertThat(holder.isRollbackOnly()).isFalse();
    }

    @Test void missingOptionalModelCannotHideAnOwnerDenial() {
        owner.optionalReference = true;
        when(models.getModelDefinition("reference")).thenReturn(Optional.empty());
        command(scope -> { denied(() -> scope.accessor().getByIdOrNull("reference", "foreign")); return null; });
        assertThat(holder.isRollbackOnly()).isTrue();
    }

    @Test void ownerIsolationIsCheckedBeforeAnySourceRead() throws Exception {
        owner.readIsolation = BusinessResourceAuthorization.ReadIsolation.READ_COMMITTED;
        when(connection.getTransactionIsolation()).thenReturn(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
        assertThatThrownBy(() -> command(scope -> null)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verify(data, never()).getByIdForAuthorization(anyString(), anyString());
    }
    @Test void ownerIsolationCannotChangeMidInvocation() throws Exception {
        owner.readIsolation = BusinessResourceAuthorization.ReadIsolation.READ_COMMITTED;
        when(connection.getTransactionIsolation()).thenReturn(java.sql.Connection.TRANSACTION_READ_COMMITTED);
        command(scope -> {
            assertThat(scope.accessor().getById("receipt", "root-1")).containsEntry("pid", "root-1");
            owner.readIsolation = BusinessResourceAuthorization.ReadIsolation.REPEATABLE_READ;
            denied(() -> scope.accessor().getById("receipt", "root-1"));
            return null;
        });
        assertThat(holder.isRollbackOnly()).isTrue();
    }

    private void pagedOwner() throws Exception {
        owner.pagedLimits = Map.of("line", 4);
        owner.readIsolation = BusinessResourceAuthorization.ReadIsolation.REPEATABLE_READ;
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_REPEATABLE_READ);
    }
    private DataPageQuery page(int number) {
        return new DataPageQuery(Map.of("parent", "root-1"), Map.of(), List.of(), number, 2);
    }
    @Test void explicitlyBoundedPagesRetainCompleteCountsAndProjectEveryRow() throws Exception {
        pagedOwner();
        var second = row("line-2", Map.of("parent", "root-1", "qty", 3, "secret_cost", 81));
        var third = row("line-3", Map.of("parent", "root-1", "qty", 4, "secret_cost", 82));
        when(data.list(eq("line"), any())).thenAnswer(call -> {
            DynamicQueryRequest request = call.getArgument(1);
            assertThat(request.getSortFields()).singleElement().satisfies(sort -> assertThat(sort.getFieldName()).isEqualTo("pid"));
            return request.getPageNum() == 1 ? new PaginationResult<>(List.of(line, second), 3L, 1, 2)
                    : new PaginationResult<>(List.of(third), 3L, 2, 2);
        });
        command(scope -> {
            var first = scope.accessor().queryPage("line", page(1));
            var next = scope.accessor().queryPage("line", page(2));
            assertThat(first.total()).isEqualTo(3); assertThat(next.total()).isEqualTo(3);
            assertThat(first.records()).hasSize(2).allSatisfy(row -> assertThat(row).doesNotContainKey("secret_cost"));
            assertThat(next.records()).singleElement().satisfies(row -> assertThat(row).containsEntry("qty", 4).doesNotContainKey("secret_cost"));
            return null;
        });
        assertThat(holder.isRollbackOnly()).isFalse();
    }
    @Test void pagedTotalsCannotExceedTheOwnerLimit() throws Exception {
        pagedOwner();
        when(data.list(eq("line"), any())).thenReturn(new PaginationResult<>(List.of(line), 5L, 1, 2));
        command(scope -> { denied(() -> scope.accessor().queryPage("line", page(1))); return null; });
        assertThat(holder.isRollbackOnly()).isTrue();
    }
    @Test void foreignRowsCannotLeakThroughAnApprovedPagedFilter() throws Exception {
        pagedOwner();
        when(data.list(eq("line"), any())).thenReturn(new PaginationResult<>(List.of(row("other", Map.of("parent", "other-root"))), 1L, 1, 2));
        command(scope -> { denied(() -> scope.accessor().queryPage("line", page(1))); return null; });
        assertThat(holder.isRollbackOnly()).isTrue();
    }

    @Test void anUnrequestedForeignPagePreventsReturningEvenTheFirstPageAndCount() throws Exception {
        pagedOwner();
        when(data.list(eq("line"), any())).thenReturn(
                new PaginationResult<>(List.of(line, row("line-2", Map.of("parent", "root-1"))), 3L, 1, 2),
                new PaginationResult<>(List.of(row("line-3", Map.of("parent", "foreign"))), 3L, 2, 2));
        command(scope -> { denied(() -> scope.accessor().queryPage("line", page(1))); return null; });
        assertThat(holder.isRollbackOnly()).isTrue();
    }
    @Test void inconsistentTotalsCannotCreateAPartialSnapshot() throws Exception {
        pagedOwner();
        when(data.list(eq("line"), any())).thenReturn(
                new PaginationResult<>(List.of(line, row("line-2", Map.of("parent", "root-1"))), 3L, 1, 2),
                new PaginationResult<>(List.of(row("line-3", Map.of("parent", "root-1"))), 4L, 2, 2));
        command(scope -> { denied(() -> scope.accessor().queryPage("line", page(1))); return null; });
        assertThat(holder.isRollbackOnly()).isTrue();
    }
    @Test void pagedReadsAreClosedByDefaultBeforeAnyNativeList() {
        command(scope -> { denied(() -> scope.accessor().queryPage("line", page(1))); return null; });
        verify(data, never()).list(anyString(), any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"empty", "sort", "keyword", "any", "oversize"})
    void pagedReadsRejectUnreviewedQueryDimensionsBeforeNativeExecution(String variant) throws Exception {
        pagedOwner();
        var query = new DataPageQuery(variant.equals("empty") ? Map.of() : Map.of("parent", "root-1"),
                variant.equals("any") ? Map.of("qty", List.<Object>of(1,2)) : Map.of(),
                variant.equals("sort") ? List.of(new DataPageQuery.Sort("pid",false)) : List.of(),
                1,variant.equals("oversize") ? 3 : 2,variant.equals("keyword") ? "secret" : "");
        command(scope -> { denied(() -> scope.accessor().queryPage("line", query)); return null; });
        verify(data,never()).list(anyString(),any());
    }
    @Test void pagedOwnersCannotUseReadCommittedOrBroadenTheInvocationDeclaration() throws Exception {
        owner.pagedLimits=Map.of("line",4);
        assertThatThrownBy(()->command(scope->null)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    @Test void aFreshInvocationCannotRaiseTheRegisteredPagedBudget() throws Exception {
        pagedOwner();
        var replacement=new Owner();replacement.readIsolation=owner.readIsolation;replacement.pagedLimits=Map.of("line",8);
        owner.invocation=replacement;
        assertThatThrownBy(()->command(scope->null)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    private com.auraboot.framework.meta.service.impl.CommandPublicOutputProjectionService publicOwner() throws Exception {
        owner.readIsolation = BusinessResourceAuthorization.ReadIsolation.REPEATABLE_READ;
        owner.publicFields = Map.of("line", Set.of("qty"));
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_REPEATABLE_READ);
        var grant = new com.auraboot.framework.permission.engine.model.BusinessGrantDecision(
                com.auraboot.framework.permission.engine.model.PermissionResult.allow(List.of()), 7L, 20L, 30L, "receipt", "root-1", List.of());
        context.setBusinessGrantDecision(grant);
        context.setPublicOutputProjection(new com.auraboot.framework.meta.service.impl.CommandPublicOutputProjectionPlan(
                context.getBusinessTargetAuthorization(), grant, Map.of("line",
                new com.auraboot.framework.meta.service.impl.CommandPublicOutputProjectionPlan.ModelFields(Set.of("qty"), Set.of("qty"), List.of(), List.of()))));
        var projection = mock(com.auraboot.framework.meta.service.impl.CommandPublicOutputProjectionService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(factory, "publicProjection", projection);
        when(projection.project(eq(context), eq("line"), anyMap())).thenAnswer(call -> {
            Map<String,Object> trusted = call.getArgument(2);
            assertThat(trusted).containsEntry("qty", 2).doesNotContainKey("secret_cost");
            return Map.of("qty", "masked");
        });
        return projection;
    }

    @Test void publicOutputUsesOnlyAnAlreadyReadHostRecordAndLeavesInternalFactsUnmasked() throws Exception {
        var projection = publicOwner();
        command(scope -> {
            var internal = scope.accessor().getById("line", "line-1");
            assertThat(internal).containsEntry("qty", 2);
            var output = scope.accessor().projectForPublicOutput("line", List.of("line-1"));
            assertThat(output).containsEntry("line-1", Map.of("qty", "masked"));
            assertThat(internal).containsEntry("qty", 2);
            assertThatThrownBy(() -> output.put("other", Map.of())).isInstanceOf(UnsupportedOperationException.class);
            return null;
        });
        verify(projection).project(eq(context), eq("line"), anyMap());
        verify(data, times(1)).getByIdForAuthorization("line", "line-1");
        assertThat(holder.isRollbackOnly()).isFalse();
    }

    @Test void publicOutputCannotInventARecordOrBorrowUnrequestedRows() throws Exception {
        var projection = publicOwner();
        command(scope -> {
            scope.accessor().getById("line", "line-1");
            denied(() -> scope.accessor().projectForPublicOutput("line", List.of("line-2")));
            return null;
        });
        verifyNoInteractions(projection);
        verify(data, never()).getByIdForAuthorization("line", "line-2");
        assertThat(holder.isRollbackOnly()).isTrue();
    }

    @Test void publicOutputRechecksOwnerMembershipBeforeProjection() throws Exception {
        var projection = publicOwner();
        command(scope -> {
            scope.accessor().getById("line", "line-1"); owner.denyLineRead = true;
            denied(() -> scope.accessor().projectForPublicOutput("line", List.of("line-1")));
            return null;
        });
        verifyNoInteractions(projection);
        assertThat(holder.isRollbackOnly()).isTrue();
    }

    @Test void publicOutputRejectsSnapshotReadsAfterAnyBusinessWrite() throws Exception {
        var projection = publicOwner();
        when(data.create(eq("ledger"), anyMap())).thenReturn(row("ledger-1", Map.of("parent", "root-1", "qty", 1)));
        command(scope -> {
            scope.accessor().getById("line", "line-1");
            scope.accessor().create("ledger", Map.of("parent", "root-1", "qty", 1));
            denied(() -> scope.accessor().projectForPublicOutput("line", List.of("line-1")));
            return null;
        });
        verifyNoInteractions(projection);
        assertThat(holder.isRollbackOnly()).isTrue();
    }

    @Test void publicOutputRequiresThePreparedDeclarationAndHostService() throws Exception {
        publicOwner(); context.setPublicOutputProjection(null);
        assertThatThrownBy(() -> command(scope -> null)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(holder.isRollbackOnly()).isTrue();
    }

    @Test void publicOutputIsClosedByDefault() {
        command(scope -> { denied(() -> scope.accessor().projectForPublicOutput("line", List.of("line-1"))); return null; });
        assertThat(holder.isRollbackOnly()).isTrue();
    }

    @Test void publicArtifactPersistenceIsClosedByDefaultAndPoisonsTheOriginalTransaction() {
        command(scope -> {
            denied(() -> scope.accessor().savePublicArtifact("file", "report.txt", "text/plain", new byte[]{1}, Map.of()));
            return null;
        });
        assertThat(holder.isRollbackOnly()).isTrue();
    }

    @Test void artifactFieldsCannotBeDeclaredWithoutTheirRootPublicView() {
        owner.artifactFields = Map.of("file", 16);
        assertThatThrownBy(() -> command(scope -> null)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(holder.isRollbackOnly()).isTrue();
    }

    @Test void aFreshInvocationCannotRaiseTheRegisteredArtifactBudget() {
        owner.artifactFields = Map.of("file", 16);
        var replacement = new Owner(); replacement.artifactFields = Map.of("file", 32); owner.invocation = replacement;
        assertThatThrownBy(() -> command(scope -> null)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test void publicOutputPlanCannotBeReplacedDuringAnInvocation() throws Exception {
        publicOwner();
        command(scope -> {
            scope.accessor().getById("line", "line-1"); context.setPublicOutputProjection(null);
            denied(() -> scope.accessor().projectForPublicOutput("line", List.of("line-1")));
            return null;
        });
        assertThat(holder.isRollbackOnly()).isTrue();
    }

    @Test void approvedChildDeletionLocksVersionAndBorrowsOnlyExactNativeTarget() {
        owner.allowDelete=true;
        doAnswer(call -> {
            assertThat(MetaContext.getCommandPermitScopeFor("line")).isEqualTo("TARGET");
            assertThat(MetaContext.getCommandPermitTargetFor("line")).isEqualTo("line-1");
            assertThat(MetaContext.getCommandExpectedVersion("line","line-1")).isEqualTo(9L);
            return null;
        }).when(data).delete("line","line-1");
        command(scope -> { scope.accessor().delete("line","line-1"); return null; });
        verify(data).delete("line","line-1");
        verify(mapper).selectTargetVersionForUpdate("mt_line","pid",7L,"line-1");
        assertThat(holder.isRollbackOnly()).isFalse();
    }
    @Test void deletionCannotRemoveRootOrUnapprovedChild() {
        command(scope -> { denied(() -> scope.accessor().delete("receipt","root-1")); return null; });
        verify(data,never()).delete(anyString(),anyString());
        assertThat(holder.isRollbackOnly()).isTrue();
    }
    @Test void deletionWithoutOwnerApprovalDoesNotReachNativeWriter() {
        command(scope -> { denied(() -> scope.accessor().delete("line","line-1")); return null; });
        verify(data,never()).delete(anyString(),anyString());
        assertThat(holder.isRollbackOnly()).isTrue();
    }
    @Test void deletionCannotBypassChildMembershipOrCurrentVersion() {
        owner.allowDelete=true; line=row("line-1",Map.of("parent","other-root"));
        command(scope -> { denied(() -> scope.accessor().delete("line","line-1")); return null; });
        verify(data,never()).delete(anyString(),anyString());
    }

    @Test void oldOwnerRemainsClosedEvenWhenItsCallbackWouldApproveDeletion() {
        owner.allowDelete=true; owner.optOutDelete=true;
        command(scope -> { denied(() -> scope.accessor().delete("line","line-1")); return null; });
        verify(data,never()).delete(anyString(),anyString());
    }

    private static final String FILE_PID = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
    private final com.auraboot.framework.file.dao.mapper.FileMapper files = mock(com.auraboot.framework.file.dao.mapper.FileMapper.class);
    private final com.auraboot.framework.file.dao.mapper.FileRelationMapper relations = mock(com.auraboot.framework.file.dao.mapper.FileRelationMapper.class);
    private final com.auraboot.framework.infrastructure.storage.StorageProvider storage = mock(com.auraboot.framework.infrastructure.storage.StorageProvider.class);
    private com.auraboot.framework.file.entity.FileEntity uploaded;
    private com.auraboot.framework.file.entity.FileRelationEntity attachment;
    private void sourceFile() {
        owner.sourceFileFields = Map.of("raw_file", 16);
        root = row("root-1", Map.of("status", "draft", "raw_file", FILE_PID));
        when(models.getModelDefinition("receipt")).thenReturn(Optional.of(ModelDefinition.builder()
                .code("receipt").sourceType("physical").tableName("mt_receipt").modelType("entity").status("published").build()));
        when(models.getModelFields("receipt")).thenReturn(List.of(FieldDefinition.builder().code("raw_file").dataType("file").build()));
        uploaded = new com.auraboot.framework.file.entity.FileEntity(); uploaded.setId(55L);uploaded.setPid(FILE_PID);uploaded.setTenantId(7L);
        uploaded.setFileName("owned.xlsx");uploaded.setFileSize(3L);uploaded.setStatus("success");uploaded.setDeletedFlag(false);
        uploaded.setStorageType(com.auraboot.framework.file.constant.StorageType.LOCAL);
        attachment = new com.auraboot.framework.file.entity.FileRelationEntity().setTenantId(7L).setEntityType("receipt")
                .setEntityId("root-1").setFieldName("raw_file").setFileId("55").setDeletedFlag(false);
        when(files.selectActiveByPidForUpdate(7L, FILE_PID)).thenReturn(uploaded);
        when(relations.lockBusinessSourceAttachment(7L, "receipt", "root-1", "raw_file", "55")).thenReturn(List.of(attachment));
        when(storage.type()).thenReturn(com.auraboot.framework.file.constant.StorageType.LOCAL);
        when(storage.download("owned.xlsx")).thenAnswer(call -> new java.io.ByteArrayInputStream(new byte[]{1,2,3}));
        org.springframework.test.util.ReflectionTestUtils.setField(factory, "sourceFiles", files);
        org.springframework.test.util.ReflectionTestUtils.setField(factory, "artifactRelations", relations);
        org.springframework.test.util.ReflectionTestUtils.setField(factory, "sourceStorage", storage);
    }
    @Test void sourceFileIsExactRootAttachmentReadOnceAndPrivateBytesNeverBecomeAReusableCapability() {
        sourceFile();
        DataAccessor[] loan = new DataAccessor[1];
        command(scope -> {
            loan[0] = scope.accessor();
            byte[] first = loan[0].readBusinessSourceFile("raw_file");
            assertThat(first).containsExactly(1,2,3); first[0]=99;
            assertThat(loan[0].readBusinessSourceFile("raw_file")).containsExactly(1,2,3);
            return null;
        });
        verify(storage, times(1)).download("owned.xlsx");
        verify(files).selectActiveByPidForUpdate(7L, FILE_PID);
        denied(() -> loan[0].readBusinessSourceFile("raw_file"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"foreign_tenant", "generated", "deleted", "pending", "oversize", "zero", "wrong_storage", "wrong_pid", "missing", "missing_relation", "duplicate_relation", "foreign_relation", "wrong_root", "wrong_field", "wrong_file", "deleted_relation", "short_bytes", "long_bytes", "virtual", "multiple", "non_file", "unpublished"})
    void invalidSourceAttachmentIsRejectedBeforeBytesCanEscape(String defect) {
        sourceFile();
        switch (defect) {
            case "foreign_tenant" -> uploaded.setTenantId(8L);
            case "generated" -> uploaded.setArtifactAuthorization("{}");
            case "deleted" -> uploaded.setDeletedFlag(true);
            case "pending" -> uploaded.setStatus("pending");
            case "oversize" -> uploaded.setFileSize(17L);
            case "zero" -> uploaded.setFileSize(0L);
            case "wrong_storage" -> uploaded.setStorageType(com.auraboot.framework.file.constant.StorageType.S3);
            case "wrong_pid" -> uploaded.setPid("01ARZ3NDEKTSV4RRFFQ69G5FAW");
            case "missing" -> when(files.selectActiveByPidForUpdate(7L, FILE_PID)).thenReturn(null);
            case "missing_relation" -> when(relations.lockBusinessSourceAttachment(7L,"receipt","root-1","raw_file","55")).thenReturn(List.of());
            case "duplicate_relation" -> when(relations.lockBusinessSourceAttachment(7L,"receipt","root-1","raw_file","55")).thenReturn(List.of(attachment,attachment));
            case "foreign_relation" -> attachment.setTenantId(8L);
            case "wrong_root" -> attachment.setEntityId("other");
            case "wrong_field" -> attachment.setFieldName("other");
            case "wrong_file" -> attachment.setFileId("56");
            case "deleted_relation" -> attachment.setDeletedFlag(true);
            case "short_bytes" -> when(storage.download("owned.xlsx")).thenReturn(new java.io.ByteArrayInputStream(new byte[]{1,2}));
            case "long_bytes" -> when(storage.download("owned.xlsx")).thenReturn(new java.io.ByteArrayInputStream(new byte[17]));
            case "virtual" -> when(models.getModelFields("receipt")).thenReturn(List.of(FieldDefinition.builder().code("raw_file").dataType("file").virtualType("transient").build()));
            case "multiple" -> when(models.getModelFields("receipt")).thenReturn(List.of(FieldDefinition.builder().code("raw_file").dataType("file").extraProps(Map.of("multiple",true)).build()));
            case "non_file" -> when(models.getModelFields("receipt")).thenReturn(List.of(FieldDefinition.builder().code("raw_file").dataType("text").build()));
            case "unpublished" -> when(models.getModelDefinition("receipt")).thenReturn(Optional.of(ModelDefinition.builder().code("receipt").tableName("mt_receipt").status("draft").build()));
            default -> throw new AssertionError(defect);
        }
        command(scope -> { denied(() -> scope.accessor().readBusinessSourceFile("raw_file")); return null; });
        assertThat(holder.isRollbackOnly()).isTrue();
        if (!Set.of("short_bytes","long_bytes").contains(defect)) verify(storage,never()).download(anyString());
    }
    @Test void oldOwnerCannotReadAnAttachmentWithoutAnExplicitSourceDeclaration() {
        sourceFile(); owner.sourceFileFields=Map.of();
        command(scope -> { denied(() -> scope.accessor().readBusinessSourceFile("raw_file")); return null; });
        verifyNoInteractions(files, relations); verify(storage,never()).download(anyString());
    }
    @Test void nullSourceFieldIsDeniedAndPoisonsTheOriginalTransaction() {
        sourceFile();
        command(scope -> { denied(() -> scope.accessor().readBusinessSourceFile(null)); return null; });
        assertThat(holder.isRollbackOnly()).isTrue();
        verifyNoInteractions(files, relations); verify(storage,never()).download(anyString());
    }
    @Test void undeclaredSourceFieldDoesNotReachFileMetadataOrStorage() {
        sourceFile();
        command(scope -> { denied(() -> scope.accessor().readBusinessSourceFile("another_file")); return null; });
        verifyNoInteractions(files, relations); verify(storage,never()).download(anyString());
    }
    @Test void sourceFileBindingAndOwnerDeclarationCannotChangeDuringTheInvocation() {
        sourceFile();
        command(scope -> {
            scope.accessor().readBusinessSourceFile("raw_file");
            root = row("root-1", Map.of("status","draft","raw_file","01ARZ3NDEKTSV4RRFFQ69G5FAW"));
            denied(() -> scope.accessor().readBusinessSourceFile("raw_file")); return null;
        });
        verify(storage,times(1)).download(anyString());
    }
    @Test void sourceFileDeclarationCannotChangeBetweenRegisteredAndInvocationOwner() {
        sourceFile();
        var replacement = new Owner(); replacement.sourceFileFields=Map.of("raw_file",32);owner.invocation=replacement;
        assertThatThrownBy(() -> command(scope -> null)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verify(storage,never()).download(anyString());
    }
    @Test void sourceFileDeclarationCannotChangeAfterOpeningEvenWhenTheFileWasAlreadyRead() {
        sourceFile();
        command(scope -> {
            scope.accessor().readBusinessSourceFile("raw_file"); owner.sourceFileFields=Map.of("raw_file",32);
            denied(() -> scope.accessor().readBusinessSourceFile("raw_file")); return null;
        });
    }

    static final class Owner implements CommandHandlerExtension, BusinessResourceAuthorization {
        RecordReadAccessor captured;
        String extraWrite;
        boolean inspectLockedLine, optionalReference, allowDelete;
        boolean optOutDelete;
        ReadIsolation readIsolation;
        Map<String,Integer> pagedLimits = Map.of();
        Map<String,Set<String>> publicFields = Map.of();
        Map<String,Integer> artifactFields = Map.of();
        Map<String,Integer> sourceFileFields = Map.of();
        Map<String,BusinessResultField> resultProjection = Map.of();
        boolean denyLineRead;
        BusinessResourceAuthorization invocation;
        public BusinessResourceAuthorization newInvocationAuthorization() { return invocation == null ? this : invocation; }
        public Map<String,Integer> pagedBusinessResourceRowLimits() { return pagedLimits; }
        public Map<String,Set<String>> publicBusinessResourceFields() { return publicFields; }
        public Map<String,Integer> businessArtifactFields() { return artifactFields; }
        public Map<String,Integer> businessSourceFileFields() { return sourceFileFields; }
        public ReadIsolation resourceReadIsolation() { return readIsolation; }
        public Set<String> optionalBusinessResourceModels() { return optionalReference ? Set.of("reference") : Set.of(); }
        public String getCommandType() { return "receipt.confirm"; }
        public Object execute(CommandContext context) { throw new AssertionError("Not called by unit fixture"); }
        public Boundary businessBoundary(){return new Boundary("receipt.confirm","receipt","update",Set.of("inventory.receipt.confirm"));}
        public Set<String> businessResourceModels() { return Set.of("receipt", "line", "ledger", "reference"); }
        public Set<String> businessResultFields() { return Set.of("status"); }
        public Map<String,BusinessResultField> businessResultProjection() { return resultProjection; }
        public Set<String> businessDeletableResourceModels() { return allowDelete && !optOutDelete ? Set.of("line") : Set.of(); }
        public Grant authorizeResource(TargetAuthorizationContext root, Request request) {
            if (denyLineRead && request.action() == Action.READ && "line".equals(request.modelCode())) return null;
            captured = root.records();
            if (inspectLockedLine) ((BusinessResourceSourceReader) root.records()).getLockedById("line", "line-1");
            if (optionalReference && request.action() == Action.READ && "reference".equals(request.modelCode())
                    && "optional-1".equals(request.recordId())) return new Grant(Set.of("pid", "tenant_id", "row_version"), Set.of(), 1);
            Set<String> read = Set.of("pid", "tenant_id", "row_version", "parent", "qty", "status");
            if (request.action() == Action.READ && ("receipt".equals(request.modelCode()) && root.recordId().equals(request.recordId())
                    || "line".equals(request.modelCode()) && root.recordId().equals(request.record().get("parent")))) return new Grant(read, Set.of(), 1);
            if (request.action() == Action.QUERY && "line".equals(request.modelCode())
                    && Map.of("parent", root.recordId()).equals(request.filters())) return new Grant(read, Set.of(), 2);
            if (allowDelete && request.action() == Action.DELETE && "line".equals(request.modelCode())
                    && root.recordId().equals(request.record().get("parent"))) return new Grant(read,Set.of(),1);
            if (request.action() == Action.UPDATE && "line".equals(request.modelCode())
                    && root.recordId().equals(request.record().get("parent"))) return new Grant(read, Set.of("qty"), 1);
            if (request.action() == Action.CREATE && "ledger".equals(request.modelCode())
                    && root.recordId().equals(request.changes().get("parent"))) {
                var write = new HashSet<>(Set.of("parent", "qty")); if (extraWrite != null) write.add(extraWrite);
                return new Grant(read, write, 1);
            }
            return null;
        }
    }
}
