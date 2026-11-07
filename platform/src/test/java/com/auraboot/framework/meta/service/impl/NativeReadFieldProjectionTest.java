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
    private final ModelDefinition model = ModelDefinition.builder().code("project").fields(List.of(
            FieldDefinition.builder().code("name").columnName("stored_name").build(),
            FieldDefinition.builder().code("secret").columnName("stored_secret").build())).build();
    @BeforeEach void context() {
        MetaContext.setContext(100L,1L,"actor","actor"); MetaContext.setMemberId(5L);
        when(transactions.getTransaction(any())).thenAnswer(call -> {
            TransactionDefinition definition = call.getArgument(0);
            assertThat(definition.isReadOnly()).isTrue();
            assertThat(definition.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            return new SimpleTransactionStatus();
        });
    }
    @AfterEach void clear() { MetaContext.clear(); }
    private BusinessGrantDecision allow(String pid,String... fields) {
        return new BusinessGrantDecision(PermissionResult.allow(List.of()),100L,1L,5L,"project",pid,
                List.of(new BusinessGrantDecision.Grant(10L,11L,"model.project.read",
                        new BusinessGrantDecision.Fields(false,Set.of(fields),Set.of()))));
    }
    @Test void evaluatesCanonicalRowsBeforeRemovingUnallowedFieldsAndColumnAliases() {
        var row = Map.<String,Object>of("pid","record","tenant_id",100L,"stored_name","Board","stored_secret","PRIVATE");
        when(authorization.resolveOrdinaryRead(5L,"project",row)).thenReturn(allow("record","name"));
        assertThat(projection.project(model,List.of(row))).containsExactly(Map.of("pid","record","stored_name","Board"));
        assertThat(row).containsEntry("stored_secret","PRIVATE");
        verify(authorization).resolveOrdinaryRead(5L,"project",row);
    }
    @Test void eachRowConsumesOnlyItsOwnMatchedGrantFields() {
        var first=Map.<String,Object>of("pid","first","name","A","secret","X");
        var second=Map.<String,Object>of("pid","second","name","B","secret","Y");
        when(authorization.resolveOrdinaryRead(5L,"project",first)).thenReturn(allow("first","name"));
        when(authorization.resolveOrdinaryRead(5L,"project",second)).thenReturn(allow("second","secret"));
        assertThat(projection.project(model,List.of(first,second))).containsExactly(
                Map.of("pid","first","name","A"),Map.of("pid","second","secret","Y"));
    }
    @Test void deniedOrMismatchedDecisionsNeverReturnUnfilteredRows() {
        var row=Map.<String,Object>of("pid","record","secret","PRIVATE");
        when(authorization.resolveOrdinaryRead(5L,"project",row)).thenReturn(allow("another","secret"));
        assertThatThrownBy(()->projection.project(model,List.of(row))).isInstanceOf(AccessDeniedException.class);
        when(authorization.resolveOrdinaryRead(5L,"project",row)).thenReturn(new BusinessGrantDecision(
                PermissionResult.deny("revoked",List.of()),100L,1L,5L,"project","record",List.of()));
        assertThatThrownBy(()->projection.project(model,List.of(row))).isInstanceOf(AccessDeniedException.class);
    }
    @Test void legacyUnrestrictedGrantPreservesTheExistingDataShape() {
        var row=Map.<String,Object>of("pid","record","secret","PRIVATE");
        when(authorization.resolveOrdinaryRead(5L,"project",row)).thenReturn(new BusinessGrantDecision(
                PermissionResult.allow(List.of()),100L,1L,5L,"project","record",List.of(
                new BusinessGrantDecision.Grant(10L,11L,"model.project.read",BusinessGrantDecision.Fields.legacy()))));
        assertThat(projection.project(model,List.of(row))).containsExactly(row);
    }
}
