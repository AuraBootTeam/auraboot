package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.meta.service.impl.*;
import com.auraboot.framework.permission.service.BusinessCommandRecordAuthorization;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RelatedMutationFieldScopeTest {
    final AsyncTaskExecutionContext.Snapshot claim=new AsyncTaskExecutionContext.Snapshot(8L,"task",100L,1L,"claimed-token");
    MockedStatic<AsyncTaskExecutionContext> task;
    final Map<String,Object> canonical=Map.of("pid","record","tenant_id",100L);
    final Map<String,Object> values=Map.of("name","approved");
    @BeforeEach void identity() {
        MetaContext.setContext(100L,1L,"actor","actor");MetaContext.setMemberId(5L);
        task=mockStatic(AsyncTaskExecutionContext.class);
        task.when(AsyncTaskExecutionContext::isActive).thenReturn(true);
        task.when(AsyncTaskExecutionContext::require).thenReturn(claim);
    }
    @AfterEach void clear() {task.close();MetaContext.clear();TransactionSynchronizationManager.clear();}
    private <T> T create(Supplier<T> action) {
        return MetaContext.runWithCommandPermitPlan("ALL",null,"child",null,
                ()->MetaContext.runWithAuthorizedCommandCode("root:approve",action));
    }
    private boolean permits(String pid,Map<String,Object> input) {
        return RelatedMutationFieldScope.permits("create","child",pid,Map.of("pid",pid,"tenant_id",100L),input,Set.of());
    }
    @Test void onlyExactFieldsValuesAndOneCreatedIdentityAreCoveredAndProofIsCleared() {
        create(()->RelatedMutationFieldScope.within("child","create",null,values,Set.of(),()->{
            assertThat(permits("record",Map.of("name","changed"))).isFalse();
            assertThat(permits("record",Map.of("name","approved","secret","extra"))).isFalse();
            assertThat(permits("record",values)).isTrue();
            assertThat(permits("other",values)).isFalse();
            assertThat(RelatedMutationFieldScope.permits("create","other","record",canonical,values,Set.of())).isFalse();
            return null;
        }));
        assertThat(permits("record",values)).isFalse();
    }
    @Test void changedClaimActorAndTenantNeverBorrowAProof() {
        create(()->RelatedMutationFieldScope.within("child","create",null,values,Set.of(),()->{
            task.when(AsyncTaskExecutionContext::require).thenReturn(new AsyncTaskExecutionContext.Snapshot(9L,"other",100L,1L,"other-token"));
            assertThat(permits("record",values)).isFalse();
            task.when(AsyncTaskExecutionContext::require).thenReturn(claim);
            MetaContext.setMemberId(6L);assertThat(permits("record",values)).isFalse();MetaContext.setMemberId(5L);
            assertThat(RelatedMutationFieldScope.permits("create","child","record",Map.of("pid","record","tenant_id",101L),values,Set.of())).isFalse();
            return null;
        }));
    }
    @Test void exceptionsAndNestedCommandsCannotRetainTheProof() {
        assertThatThrownBy(()->create(()->RelatedMutationFieldScope.within("child","create",null,values,Set.of(),()->{
            MetaContext.runWithAuthorizedCommandCode("other:command",()->{assertThat(permits("record",values)).isFalse();return null;});
            assertThat(permits("record",values)).isTrue();throw new IllegalStateException("native failure");
        }))).isInstanceOf(IllegalStateException.class);
        assertThat(permits("record",values)).isFalse();
    }
    @Test void conditionalUpdateBindsTheExactTargetAndObservedFieldSet() {
        MetaContext.runWithCommandPermitPlan("TARGET",4L,"child","record",()->MetaContext.runWithAuthorizedCommandCode("root:approve",()->
            RelatedMutationFieldScope.within("child","update","record",values,Set.of("revision"),()->{
                assertThat(RelatedMutationFieldScope.permits("update","child","record",canonical,values,Set.of("revision"))).isTrue();
                assertThat(RelatedMutationFieldScope.permits("update","child","record",canonical,values,Set.of("secret"))).isFalse();
                assertThat(RelatedMutationFieldScope.permits("update","child","other",Map.of("pid","other","tenant_id",100L),values,Set.of("revision"))).isFalse();
                return null;
            })));
    }
    @Test void mutableNestedInputsCannotBroadenTheFrozenDecision() {
        var nested=new LinkedHashMap<String,Object>(Map.of("supplier","approved"));
        var submitted=new LinkedHashMap<String,Object>(Map.of("snapshot",nested));
        create(()->RelatedMutationFieldScope.within("child","create",null,submitted,Set.of(),()->{
            nested.put("supplier","changed");assertThat(permits("record",submitted)).isFalse();return null;
        }));
    }
    @Test void nativeBoundaryKeepsLocksUnknownFieldsAndImmutableEnvelopeChecks() {
        MetaModelService metadata=mock(MetaModelService.class);QueryBuilderService queries=mock(QueryBuilderService.class);
        DynamicDataMapper rows=mock(DynamicDataMapper.class);BusinessCommandRecordAuthorization grants=mock(BusinessCommandRecordAuthorization.class);
        var boundary=new NativeRecordFieldAuthorization(metadata,queries,rows,grants);
        var model=ModelDefinition.builder().code("child").tableName("mt_child").fields(List.of(
                FieldDefinition.builder().code("pid").columnName("pid").build(),
                FieldDefinition.builder().code("tenant_id").columnName("tenant_id").build(),
                FieldDefinition.builder().code("name").columnName("name").build())).build();
        when(metadata.getModelDefinition("child")).thenReturn(Optional.of(model));
        when(metadata.getPrimaryKeyField("child")).thenReturn(FieldDefinition.builder().code("pid").columnName("pid").build());
        when(queries.buildConditionQuery(eq(model),anyList())).thenAnswer(call->new QueryBuilderServiceImpl(rows).buildConditionQuery(model,call.getArgument(1)));
        when(rows.selectRecordWriterTargetsForUpdate(anyString(),eq("pid"),anyString(),anyMap())).thenReturn(List.of(Map.of("pid","record")));
        when(rows.selectByQuery(anyString(),anyMap())).thenReturn(List.of(Map.of("name","approved","__ab_auth_pid","record","__ab_auth_tenant",100L,"__ab_auth_creator",1L)));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        create(()->{
            RelatedMutationFieldScope.within("child","create",null,values,Set.of(),()->{boundary.requireCreated("child","record",values);return null;});
            assertThatThrownBy(()->boundary.requireCreated("child","record",values)).isInstanceOf(AccessDeniedException.class);
            for(var invalid:List.of(Map.<String,Object>of("rogue","x"),Map.<String,Object>of("tenant_id",100L)))
                assertThatThrownBy(()->RelatedMutationFieldScope.within("child","create",null,invalid,Set.of(),()->{
                    boundary.requireCreated("child","record",invalid);return null;
                })).isInstanceOf(AccessDeniedException.class);
            return null;
        });
        verify(rows,atLeastOnce()).selectRecordWriterTargetsForUpdate(eq("mt_child"),eq("pid"),eq("native_target_pid"),anyMap());
    }
}
