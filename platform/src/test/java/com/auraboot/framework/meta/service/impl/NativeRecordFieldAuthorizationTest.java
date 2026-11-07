package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.permission.engine.model.BusinessGrantDecision;
import com.auraboot.framework.permission.engine.model.PermissionResult;
import com.auraboot.framework.permission.service.BusinessCommandRecordAuthorization;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class NativeRecordFieldAuthorizationTest {
    final MetaModelService metadata = mock(MetaModelService.class);
    final QueryBuilderService queries = mock(QueryBuilderService.class);
    final DynamicDataMapper rows = mock(DynamicDataMapper.class);
    final BusinessCommandRecordAuthorization authorization = mock(BusinessCommandRecordAuthorization.class);
    final NativeRecordFieldAuthorization boundary = new NativeRecordFieldAuthorization(metadata,queries,rows,authorization);
    final ModelDefinition model = ModelDefinition.builder().code("project").tableName("mt_project").fields(List.of(
            FieldDefinition.builder().code("pid").columnName("pid").build(),
            FieldDefinition.builder().code("tenant_id").columnName("tenant_id").build(),
            FieldDefinition.builder().code("deleted_flag").columnName("deleted_flag").build(),
            FieldDefinition.builder().code("name").columnName("stored_name").build(),
            FieldDefinition.builder().code("secret").columnName("stored_secret").build(),
            FieldDefinition.builder().code("owner_alias").columnName("created_by").build())).build();
    final Map<String,Object> raw = new LinkedHashMap<>(Map.of("stored_name","before","stored_secret","PRIVATE",
            "__ab_auth_pid","record","__ab_auth_tenant",100L,"__ab_auth_creator",1L,"row_version",4L));
    @BeforeEach void context() {
        MetaContext.setContext(100L,1L,"actor","actor");MetaContext.setMemberId(5L);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        when(metadata.getModelDefinition("project")).thenReturn(Optional.of(model));
        when(metadata.getPrimaryKeyField("project")).thenReturn(FieldDefinition.builder().code("pid").columnName("pid").build());
        when(queries.buildConditionQuery(eq(model),anyList())).thenAnswer(call ->
                new QueryBuilderServiceImpl(rows).buildConditionQuery(model,call.getArgument(1)));
        when(rows.selectByQuery(anyString(),anyMap())).thenReturn(List.of(raw));
        when(rows.selectRecordWriterTargetsForUpdate(anyString(),eq("pid"),eq("native_target_pid"),anyMap()))
                .thenReturn(List.of(Map.of("pid","record")));
    }
    @AfterEach void clear() {MetaContext.clear();TransactionSynchronizationManager.clear();}
    private BusinessGrantDecision allowed(String pid) {
        return new BusinessGrantDecision(PermissionResult.allow(List.of()),100L,1L,5L,"project",pid,List.of(
                new BusinessGrantDecision.Grant(10L,11L,"model.project.update",
                        new BusinessGrantDecision.Fields(false,Set.of(),Set.of("name")))));
    }
    @Test void allowedLogicalAndPhysicalNamesUseTheUnprojectedLockedRecord() {
        when(authorization.resolveOrdinaryUpdate(eq(5L),eq("project"),anyMap())).thenReturn(allowed("record"));
        boundary.requireUpdate("project","record",Map.of("name","after"));
        boundary.requireUpdate("project","record",Map.of("stored_name","after"));
        verify(rows,times(2)).selectByQuery(argThat(sql -> !sql.contains("FOR UPDATE") && sql.contains("__ab_auth_tenant")),anyMap());
        verify(rows,times(2)).selectRecordWriterTargetsForUpdate(eq("mt_project"),eq("pid"),eq("native_target_pid"),
                argThat(target -> target.get("tenant_id").equals(100L) && target.get("pid").equals("record")));
        verify(authorization,times(2)).resolveOrdinaryUpdate(eq(5L),eq("project"),argThat(facts ->
                "PRIVATE".equals(facts.get("secret")) && Long.valueOf(100).equals(facts.get("tenant_id"))
                        && !facts.containsKey("__ab_auth_pid")));
        assertThat(raw).containsEntry("stored_secret","PRIVATE");
    }
    @Test void mixedOrHiddenUnchangedFieldsAreRejectedWithoutAValueOracle() {
        when(authorization.resolveOrdinaryUpdate(eq(5L),eq("project"),anyMap())).thenReturn(allowed("record"));
        assertThatThrownBy(() -> boundary.requireUpdate("project","record",Map.of("name","after","secret","changed")))
                .isInstanceOf(AccessDeniedException.class).hasMessageContaining("FIELD_WRITE_DENIED");
        assertThatThrownBy(() -> boundary.requireUpdate("project","record",Map.of("secret","PRIVATE")))
                .isInstanceOf(AccessDeniedException.class);
    }
    @Test void missingOrWrongRecordGrantDoesNotAuthorizeAnyField() {
        when(authorization.resolveOrdinaryUpdate(eq(5L),eq("project"),anyMap())).thenReturn(allowed("other"));
        assertThatThrownBy(() -> boundary.requireUpdate("project","record",Map.of("name","after"))).isInstanceOf(AccessDeniedException.class);
        when(authorization.resolveOrdinaryUpdate(eq(5L),eq("project"),anyMap())).thenReturn(null);
        assertThatThrownBy(() -> boundary.requireUpdate("project","record",Map.of("name","after"))).isInstanceOf(AccessDeniedException.class);
    }
    @Test void weakOrReadonlyTransactionsCannotSpliceRowsAndGrants() {
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThatThrownBy(() -> boundary.requireUpdate("project","record",Map.of("name","after"))).isInstanceOf(AccessDeniedException.class);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThatThrownBy(() -> boundary.requireUpdate("project","record",Map.of("name","after"))).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(rows,authorization);
    }
    @Test void unknownFieldsAndChangedEnvelopeFactsAreNeverGranted() {
        when(authorization.resolveOrdinaryUpdate(eq(5L),eq("project"),anyMap())).thenReturn(allowed("record"));
        assertThatThrownBy(() -> boundary.requireUpdate("project","record",Map.of("rogue","after"))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> boundary.requireUpdate("project","record",Map.of("tenant_id",101L))).isInstanceOf(AccessDeniedException.class);
        boundary.requireUpdate("project","record",Map.of("pid","record","_expectedVersion",4L,"row_version",4L,"name","after"));
    }
    @Test void auditFactsCannotBeProbedOrRenamedIntoWritableBusinessFields() {
        when(authorization.resolveOrdinaryUpdate(eq(5L),eq("project"),anyMap())).thenReturn(
                new BusinessGrantDecision(PermissionResult.allow(List.of()),100L,1L,5L,"project","record",List.of(
                        new BusinessGrantDecision.Grant(10L,11L,"model.project.update",BusinessGrantDecision.Fields.legacy()))));
        for (long actor : List.of(1L,99L)) {
            assertThatThrownBy(() -> boundary.requireUpdate("project","record",Map.of("created_by",actor)))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> boundary.requireUpdate("project","record",Map.of("owner_alias",actor)))
                    .isInstanceOf(AccessDeniedException.class);
        }
        boundary.requireUpdate("project","record",Map.of("secret","new-business-value"));
    }

    @Test void creationUsesActualInsertedFactsAndItsOwnCreateFields() {
        when(authorization.resolveOrdinaryCreate(eq(5L),eq("project"),anyMap())).thenReturn(
                new BusinessGrantDecision(PermissionResult.allow(List.of()),100L,1L,5L,"project","record",List.of(
                        new BusinessGrantDecision.Grant(10L,12L,"model.project.create",
                                new BusinessGrantDecision.Fields(false,Set.of(),Set.of("name"))))));
        boundary.requireCreated("project","record",Map.of("name","new"));
        assertThatThrownBy(() -> boundary.requireCreated("project","record",Map.of("name","new","secret","forbidden")))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> boundary.requireCreated("project","record",Map.of("name","new","row_version",4L)))
                .isInstanceOf(AccessDeniedException.class);
        verify(authorization,atLeastOnce()).resolveOrdinaryCreate(eq(5L),eq("project"),argThat(facts ->
                Long.valueOf(1).equals(facts.get("created_by")) && Long.valueOf(100).equals(facts.get("tenant_id"))));
        verify(authorization,never()).resolveOrdinaryUpdate(any(),any(),any());
    }

    @Test void creationCannotBorrowUpdateFieldsOrAcceptForgedOwnership() {
        when(authorization.resolveOrdinaryUpdate(eq(5L),eq("project"),anyMap())).thenReturn(allowed("record"));
        assertThatThrownBy(() -> boundary.requireCreated("project","record",Map.of("name","new")))
                .isInstanceOf(AccessDeniedException.class);
        raw.put("__ab_auth_creator",99L);
        assertThatThrownBy(() -> boundary.requireCreated("project","record",Map.of("name","new")))
                .isInstanceOf(AccessDeniedException.class).hasMessageContaining("ownership");
        verify(authorization,never()).resolveOrdinaryUpdate(any(),any(),any());
    }

}
