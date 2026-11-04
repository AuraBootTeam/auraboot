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

    static final class Owner implements CommandHandlerExtension, BusinessResourceAuthorization {
        RecordReadAccessor captured;
        String extraWrite;
        boolean inspectLockedLine, optionalReference;
        public Set<String> optionalBusinessResourceModels() { return optionalReference ? Set.of("reference") : Set.of(); }
        public String getCommandType() { return "receipt.confirm"; }
        public Object execute(CommandContext context) { throw new AssertionError("Not called by unit fixture"); }
        public Boundary businessBoundary(){return new Boundary("receipt.confirm","receipt","update",Set.of("inventory.receipt.confirm"));}
        public Set<String> businessResourceModels() { return Set.of("receipt", "line", "ledger", "reference"); }
        public Set<String> businessResultFields() { return Set.of("status"); }
        public Grant authorizeResource(TargetAuthorizationContext root, Request request) {
            captured = root.records();
            if (inspectLockedLine) ((BusinessResourceSourceReader) root.records()).getLockedById("line", "line-1");
            if (optionalReference && request.action() == Action.READ && "reference".equals(request.modelCode())
                    && "optional-1".equals(request.recordId())) return new Grant(Set.of("pid", "tenant_id", "row_version"), Set.of(), 1);
            Set<String> read = Set.of("pid", "tenant_id", "row_version", "parent", "qty", "status");
            if (request.action() == Action.READ && ("receipt".equals(request.modelCode()) && root.recordId().equals(request.recordId())
                    || "line".equals(request.modelCode()) && root.recordId().equals(request.record().get("parent")))) return new Grant(read, Set.of(), 1);
            if (request.action() == Action.QUERY && "line".equals(request.modelCode())
                    && Map.of("parent", root.recordId()).equals(request.filters())) return new Grant(read, Set.of(), 2);
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
