package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.permission.engine.model.BusinessGrantDecision;
import com.auraboot.framework.permission.engine.model.PermissionResult;
import com.auraboot.framework.permission.service.BusinessCommandRecordAuthorization;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class NativeReadFieldProjectionTest {
    private final BusinessCommandRecordAuthorization authorization = mock(BusinessCommandRecordAuthorization.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final NativeReadFieldProjection projection = new NativeReadFieldProjection(authorization, transactions);
    private final ModelDefinition model = ModelDefinition.builder().code("project").tableName("mt_project").fields(List.of(
            FieldDefinition.builder().code("name").columnName("stored_name").build(),
            FieldDefinition.builder().code("secret").columnName("stored_secret").build())).build();
    @BeforeEach void context() {
        MetaContext.setContext(100L,1L,"actor","actor"); MetaContext.setMemberId(5L);
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        org.springframework.transaction.support.TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        org.springframework.transaction.support.TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        when(transactions.getTransaction(any())).thenAnswer(call -> {
            TransactionDefinition definition = call.getArgument(0);
            assertThat(definition.isReadOnly()).isTrue();
            assertThat(definition.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            return new SimpleTransactionStatus();
        });
    }
    @AfterEach void clear() { MetaContext.clear(); org.springframework.transaction.support.TransactionSynchronizationManager.clear(); }
    private BusinessGrantDecision allow(String pid,String... fields) {
        return new BusinessGrantDecision(PermissionResult.allow(List.of()),100L,1L,5L,"project",pid,
                List.of(new BusinessGrantDecision.Grant(10L,11L,"model.project.read",
                        new BusinessGrantDecision.Fields(false,Set.of(fields),Set.of()))));
    }
    private Map<String,Object> row(Map<String,Object> data) {
        var row = new LinkedHashMap<>(data);
        row.put("__ab_auth_pid",data.get("pid")); row.put("__ab_auth_tenant",100L); row.put("__ab_auth_creator",1L);
        return row;
    }
    @Test void evaluatesCanonicalRowsBeforeRemovingUnallowedFieldsAndColumnAliases() {
        var row = row(Map.<String,Object>of("pid","record","stored_name","Board","stored_secret","PRIVATE"));
        when(authorization.resolveOrdinaryRead(eq(5L),eq("project"),anyMap())).thenReturn(allow("record","name"));
        assertThat(projection.project(model,List.of(row))).containsExactly(Map.of("pid","record","stored_name","Board"));
        assertThat(row).containsEntry("stored_secret","PRIVATE");
        verify(authorization).resolveOrdinaryRead(eq(5L),eq("project"),argThat(value ->
                value.get("tenant_id").equals(100L) && value.get("created_by").equals(1L)
                        && value.get("secret").equals("PRIVATE") && !value.containsKey("__ab_auth_pid")));
    }
    @Test void eachRowConsumesOnlyItsOwnMatchedGrantFields() {
        var first=row(Map.<String,Object>of("pid","first","name","A","secret","X"));
        var second=row(Map.<String,Object>of("pid","second","name","B","secret","Y"));
        when(authorization.resolveOrdinaryRead(eq(5L),eq("project"),argThat(value -> "first".equals(value.get("pid"))))).thenReturn(allow("first","name"));
        when(authorization.resolveOrdinaryRead(eq(5L),eq("project"),argThat(value -> "second".equals(value.get("pid"))))).thenReturn(allow("second","secret"));
        assertThat(projection.project(model,List.of(first,second))).containsExactly(
                Map.of("pid","first","name","A"),Map.of("pid","second","secret","Y"));
    }
    @Test void deniedOrMismatchedDecisionsNeverReturnUnfilteredRows() {
        var row=row(Map.<String,Object>of("pid","record","secret","PRIVATE"));
        when(authorization.resolveOrdinaryRead(eq(5L),eq("project"),anyMap())).thenReturn(allow("another","secret"));
        assertThatThrownBy(()->projection.project(model,List.of(row))).isInstanceOf(AccessDeniedException.class);
        when(authorization.resolveOrdinaryRead(eq(5L),eq("project"),anyMap())).thenReturn(new BusinessGrantDecision(
                PermissionResult.deny("revoked",List.of()),100L,1L,5L,"project","record",List.of()));
        assertThatThrownBy(()->projection.project(model,List.of(row))).isInstanceOf(AccessDeniedException.class);
    }
    @Test void legacyUnrestrictedGrantPreservesTheExistingDataShape() {
        var row=row(Map.<String,Object>of("pid","record","secret","PRIVATE"));
        when(authorization.resolveOrdinaryRead(eq(5L),eq("project"),anyMap())).thenReturn(new BusinessGrantDecision(
                PermissionResult.allow(List.of()),100L,1L,5L,"project","record",List.of(
                new BusinessGrantDecision.Grant(10L,11L,"model.project.read",BusinessGrantDecision.Fields.legacy()))));
        assertThat(projection.project(model,List.of(row))).containsExactly(Map.of("pid","record","secret","PRIVATE"));
    }
    @Test void missingHostFactsAndUnpublishedFieldsDoNotBecomeReadable() {
        var raw = Map.<String,Object>of("pid","record","rogue","PRIVATE");
        assertThatThrownBy(() -> projection.project(model,List.of(raw))).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(authorization);
        when(authorization.resolveOrdinaryRead(eq(5L),eq("project"),anyMap())).thenReturn(allow("record","rogue"));
        assertThat(projection.project(model,List.of(row(raw)))).containsExactly(Map.of("pid","record"));
    }

    @Test void queryAddsOnlyFixedHostFactsWithoutReplacingTheBusinessSelection() {
        var builder = new QueryBuilderServiceImpl(mock(com.auraboot.framework.meta.mapper.DynamicDataMapper.class))
                .buildConditionQuery(model,List.of());
        var original = List.copyOf(builder.getSelectFields());
        NativeReadFieldProjection.prepareQuery(model,builder);
        assertThat(builder.getSql()).contains("stored_name", "pid AS __ab_auth_pid", "tenant_id AS __ab_auth_tenant", "created_by AS __ab_auth_creator");
        NativeReadFieldProjection.prepareQuery(model,builder);
        assertThat(builder.getSelectFields()).containsAll(original).hasSize(original.size()+3);
    }

    @Test void envelopeKeepsOptimisticVersionWithoutGrantingAuditOrBusinessSecrets() {
        var value=row(Map.<String,Object>of("pid","record","name","Board","secret","PRIVATE","created_by",99L,"row_version",7L));
        when(authorization.resolveOrdinaryRead(eq(5L),eq("project"),anyMap())).thenReturn(allow("record","name"));
        assertThat(projection.project(model,List.of(value))).containsExactly(Map.of("pid","record","name","Board","row_version",7L));
    }

    @Test void independentReadStartsSnapshotBeforeInvokingTheRecordLoader() {
        org.springframework.transaction.support.TransactionSynchronizationManager.clear();
        assertThat(projection.readWithinSnapshot("project", () -> "loaded")).isEqualTo("loaded");
        verify(transactions).getTransaction(any());
        verify(transactions).commit(any());
    }

    @Test void coherentReadSnapshotIsNotReplacedAfterLoadingTheRow() {
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        org.springframework.transaction.support.TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        org.springframework.transaction.support.TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            when(authorization.resolveOrdinaryRead(eq(5L),eq("project"),anyMap())).thenReturn(allow("record","name"));
            assertThat(projection.readWithinSnapshot("project", () -> projection.project(model,
                    List.of(row(Map.of("pid","record","name","Board","secret","PRIVATE"))))))
                    .containsExactly(Map.of("pid","record","name","Board"));
            verifyNoInteractions(transactions);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clear();
        }
    }

    @Test void ambientWriteReadbackIsNotSilentlyReplacedByACommittedHistoricalRow() {
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        org.springframework.transaction.support.TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        try {
            when(authorization.resolveOrdinaryRead(eq(5L),eq("project"),anyMap())).thenReturn(allow("record","name"));
            assertThat(projection.readWithinSnapshot("project", () -> projection.project(model,
                    List.of(row(Map.of("pid","record","name","own-uncommitted-row","secret","PRIVATE"))))))
                    .containsExactly(Map.of("pid","record","name","own-uncommitted-row"));
            verifyNoInteractions(transactions);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clear();
        }
    }

    @Test void weakWriteTransactionIsRejectedBeforeLoadingRowsOrOpeningAnotherSnapshot() {
        var invoked = new java.util.concurrent.atomic.AtomicBoolean();
        org.springframework.transaction.support.TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        org.springframework.transaction.support.TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThatThrownBy(() -> projection.readWithinSnapshot("project", () -> { invoked.set(true); return "row"; }))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> projection.project(model,List.of(row(Map.of("pid","record","name","unsafe")))))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(invoked).isFalse();
        verifyNoInteractions(transactions,authorization);
    }

    @Test void anAlreadyLoadedRowCannotStartANewGrantSnapshotAtProjectionTime() {
        org.springframework.transaction.support.TransactionSynchronizationManager.clear();
        assertThatThrownBy(() -> projection.project(model,List.of(row(Map.of("pid","record","name","stale")))))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(transactions,authorization);
    }

    @Test void ordinaryWriteResultsUseThePublicReaderInsteadOfTheInternalSnapshot() {
        var actual = Map.<String,Object>of("pid","record","tenant_id",100L,"row_version",7L,"name","Board","secret","PRIVATE");
        var visible = Map.<String,Object>of("pid","record","row_version",7L,"name","Board");
        assertThat(projection.publicWriteResult(model,actual,() -> visible)).isEqualTo(visible);
        assertThat(actual).containsEntry("secret","PRIVATE");
    }

    @Test void writeOnlyResultsAreAnEnvelopeAndNeverMaskStorageFailures() {
        var actual = Map.<String,Object>of("pid","record","tenant_id",100L,"row_version",7L,"secret","PRIVATE");
        assertThat(projection.publicWriteResult(model,actual,() -> {throw new AccessDeniedException("read denied");}))
                .containsExactlyInAnyOrderEntriesOf(Map.of("pid","record","row_version",7L));
        assertThatThrownBy(() -> projection.publicWriteResult(model,actual,() -> {throw new IllegalStateException("storage failed");}))
                .isInstanceOf(IllegalStateException.class).hasMessage("storage failed");
        assertThatThrownBy(() -> projection.publicWriteResult(model,actual,() -> Map.of("pid","other")))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> projection.publicWriteResult(model,Map.of("pid","record","tenant_id",101L),() -> Map.of("pid","record")))
                .isInstanceOf(AccessDeniedException.class);
    }

}
