package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Automatic state transitions must inspect both the child and its persisted parent. */
class CommandStateOwnershipTest {
    final DynamicDataMapper mapper=mock(DynamicDataMapper.class);
    final MetaModelService metadata=mock(MetaModelService.class);
    final CommandStateCheckExecutor executor=new CommandStateCheckExecutor(mapper,metadata,null,null,new CommandSpelEvaluator());
    void setup(boolean managedParent) {
        MetaContext.setContext(1L,7L,null,"system");
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        var model=RecordCommandWriterParentTest.childModel("mt_child");
        var fields=new ArrayList<>(model.getFields());fields.add(FieldDefinition.builder().code("status").dataType("string").build());model.setFields(fields);
        when(metadata.getModelDefinition("line")).thenReturn(Optional.of(model));
        when(metadata.getModelDefinition("order")).thenReturn(Optional.of(RecordCommandWriterParentTest.parentModel("mt_parent")));
        when(metadata.getTableName("line")).thenReturn("mt_child");
        when(mapper.selectByQueryWithoutTenant(anyString(),anyMap())).thenReturn(List.of(Map.of("status","draft")));
        when(mapper.selectRecordWriterTargetsForUpdate(eq("mt_child"),eq("order_pid"),eq("record_writer_parent_pid"),anyMap()))
            .thenReturn(List.of(Map.of("record_writer_parent_pid","parent-a")));
        when(mapper.selectRecordWriterTargetsForUpdate(eq("mt_parent"),eq("managed_flag"),eq("managed"),anyMap()))
            .thenReturn(List.of(Map.of("managed",managedParent)));
        lenient().when(mapper.selectRecordWriterTargetsForUpdate(eq("mt_child"),eq("managed_flag"),eq("managed"),anyMap()))
            .thenReturn(List.of(Map.of("id",7L,"pid","line-a","managed",false)));
        lenient().when(mapper.update(anyString(),anyMap(),anyMap())).thenReturn(1);
    }
    String transition() {
        var command=new CommandDefinition();command.setCode("app:save");command.setModelCode("line");
        var request=new CommandExecuteRequest();request.setTargetRecordId("line-a");
        return executor.executeStateCheckPhase(command,Map.of(),1L,request,Map.of("stateField","status","toState","approved"));
    }
    @AfterEach void clear() { MetaContext.clear();org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false); }
    @Test void genericTransitionCannotChangeChildOfManagedParent() {
        setup(true);assertThatThrownBy(this::transition).hasMessageContaining("RECORD_WRITER_DENIED");
        verify(mapper,never()).update(anyString(),anyMap(),anyMap());
    }
    @Test void ordinaryParentRetainsTransition() {
        setup(false);assertThat(transition()).isEqualTo("approved");
        verify(mapper).update("mt_child",Map.of("status","approved"),Map.of("tenant_id",1L,"pid","line-a"));
    }
    @Test void exactWriterMayTransitionChildOfManagedParent() {
        setup(true);MetaContext.runWithCommandPermitPlan("ALL",null,"line","line-a",()->
            MetaContext.runWithAuthorizedCommandCode("app:save",()->assertThat(transition()).isEqualTo("approved")));
        verify(mapper).update("mt_child",Map.of("status","approved"),Map.of("tenant_id",1L,"pid","line-a"));
    }
}
