package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.DataDomainService;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Hermetic boundary evidence; PostgreSQL row outcomes are verified separately. */
class RecordCommandWriterSqlBoundaryTest {
    @AfterEach void clear() { MetaContext.clear(); org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false); }
    private ModelDefinition model() {
        return ModelDefinition.builder().code("shared_bid").tableName("mt_shared_bid")
            .fields(List.of(FieldDefinition.builder().code("managed").columnName("managed_flag").dataType("boolean").build()))
            .extension(Map.of("recordCommandWriters",Map.of("field","managed","commands",
                Map.of("create",List.of("app:dispatch"),"update",List.of("app:save"),"delete",List.of())))).build();
    }
    @Test void updateChokepointKeepsOwnershipTogetherWithTenantAndCasPredicates() {
        MetaContext.setContext(1L,7L,null,"system");
        var mapper=mock(DynamicDataMapper.class);
        var support=new DynamicScopedWriteSupport(mapper,mock(DataPermissionEngine.class),mock(DataDomainService.class));
        support.executeScopedUpdate(model(),"shared_bid","pid","bid-a",Map.of("amount",12),java.util.Set.of(),4L);
        var sql=ArgumentCaptor.forClass(String.class);
        verify(mapper).updateByQuery(sql.capture(),anyMap());
        assertThat(sql.getValue()).contains("tenant_id = #{params.tenantId}","row_version = #{params.expectedVersion}",
            "managed_flag IS DISTINCT FROM TRUE","row_version = row_version + 1");
    }
    @Test void exactWriterMayUpdateButDoesNotAcquireDeletionPermission() {
        MetaContext.setContext(1L,7L,null,"system");
        var mapper=mock(DynamicDataMapper.class);
        var support=new DynamicScopedWriteSupport(mapper,mock(DataPermissionEngine.class),mock(DataDomainService.class));
        MetaContext.runWithCommandPermitPlan("ALL",null,"shared_bid","bid-a",()->
            MetaContext.runWithAuthorizedCommandCode("app:save",()->{
                support.executeScopedUpdate(model(),"shared_bid","pid","bid-a",Map.of("amount",12),java.util.Set.of(),null);
                support.executeScopedDelete(model(),"shared_bid","pid","bid-a",null);
            }));
        var update=ArgumentCaptor.forClass(String.class);var delete=ArgumentCaptor.forClass(String.class);
        verify(mapper).updateByQuery(update.capture(),anyMap());verify(mapper).deleteByQuery(delete.capture(),anyMap());
        assertThat(update.getValue()).doesNotContain("managed_flag IS DISTINCT");
        assertThat(delete.getValue()).contains("managed_flag IS DISTINCT FROM TRUE","tenant_id = #{params.tenantId}");
    }
    @Test void physicalInputCannotForgeMarkerBeforeMapperMutation() {
        MetaContext.setContext(1L,7L,null,"system");
        var mapper=mock(DynamicDataMapper.class);
        var support=new DynamicScopedWriteSupport(mapper,mock(DataPermissionEngine.class),mock(DataDomainService.class));
        assertThatThrownBy(()->support.executeScopedUpdate(model(),"shared_bid","pid","bid-a",Map.of("managed_flag",true),java.util.Set.of(),null))
            .hasMessageContaining("RECORD_WRITER_DENIED");
        verifyNoInteractions(mapper);
    }
    @Test void legacyMapperTargetsAreLockedAndMarkedRowsAreDeniedBeforeSideEffects() {
        MetaContext.setContext(1L,7L,null,"system");
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        var mapper=mock(DynamicDataMapper.class);
        when(mapper.selectByQuery(anyString(),anyMap())).thenReturn(List.of(Map.of("managed",true)));
        assertThatThrownBy(()->RecordCommandWriterGuard.guardLegacyConditions(mapper,model(),"mt_shared_bid",Map.of("pid","bid-a"),"update"))
            .hasMessageContaining("RECORD_WRITER_DENIED");
        var sql=ArgumentCaptor.forClass(String.class);verify(mapper).selectByQuery(sql.capture(),anyMap());
        assertThat(sql.getValue()).contains("tenant_id =", "managed_flag AS managed", "FOR UPDATE");
        verify(mapper,never()).update(anyString(),anyMap(),anyMap());
    }
    @Test void ordinaryLegacyTargetsRetainTheirMappingAndReceiveTenantScope() {
        MetaContext.setContext(1L,7L,null,"system");
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        var mapper=mock(DynamicDataMapper.class);
        when(mapper.selectByQuery(anyString(),anyMap())).thenReturn(List.of(Map.of("managed",false)));
        var scoped=RecordCommandWriterGuard.guardLegacyConditions(mapper,model(),"mt_shared_bid",Map.of("pid","ordinary"),"update");
        assertThat(scoped).containsEntry("tenant_id",1L).containsEntry("pid","ordinary");
    }
    @Test void legacyPolicyCannotRunWithoutTransactionOrAgainstAnotherTenant() {
        MetaContext.setContext(1L,7L,null,"system");var mapper=mock(DynamicDataMapper.class);
        assertThatThrownBy(()->RecordCommandWriterGuard.guardLegacyConditions(mapper,model(),"mt_shared_bid",Map.of("pid","bid-a"),"delete"))
            .hasMessageContaining("active transaction");
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(()->RecordCommandWriterGuard.guardLegacyConditions(mapper,model(),"mt_shared_bid",Map.of("tenant_id",2L,"pid","bid-a"),"delete"))
            .hasMessageContaining("RECORD_WRITER_DENIED");
        verifyNoInteractions(mapper);
    }

}
