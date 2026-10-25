package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.DocumentFlowService;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommandDeleteSideEffectContextTest {
    DynamicDataMapper mapper;
    MetaModelService models;
    CommandSideEffectExecutor executor;
    CommandDefinition command;
    CommandExecuteRequest request;
    Map<String,Object> config;
    @BeforeEach void setup() {
        mapper=mock(DynamicDataMapper.class);models=mock(MetaModelService.class);
        executor=new CommandSideEffectExecutor(mapper,mock(DynamicDataService.class),models,
                mock(CommandSpelEvaluator.class),mock(DocumentFlowService.class),new ObjectMapper(),
                mock(CommandModelMutationSupport.class));
        when(models.getTableName("pm_order_line")).thenReturn("mt_pm_order_line");
        when(models.getTableName("pm_order")).thenReturn("mt_pm_order");
        when(mapper.selectByQuery(anyString(),anyMap())).thenAnswer(call ->
                ((String)call.getArgument(0)).startsWith("SELECT amount FROM")
                        ? List.of(Map.of("amount",new BigDecimal("2.50"))) : List.of());
        when(mapper.update(anyString(),anyMap(),anyMap())).thenReturn(1);
        command=new CommandDefinition();command.setModelCode("pm_order_line");
        request=new CommandExecuteRequest();request.setTargetRecordPid("line-1");request.setTargetRecordId("line-1");request.setOperationType("delete");
        config=Map.of("type","delete","sideEffects",List.of(Map.of("action","aggregate",
                "targetModel","pm_order","childModel","pm_order_line","childField","amount",
                "parentField","total_amount","parentFk","order_id","function","sum")));
    }
    Map<String,Object> original() {return new HashMap<>(Map.of("pid","line-1","id",77L,
            "tenant_id",1L,"order_id","parent-real","amount",new BigDecimal("7.50")));}
    void run(Map<String,Object> payload,Map<String,Object> before) {
        executor.executeSideEffectPhase(config,payload,1L,100L,command,request,Map.of(),before);
    }
    @Test void deletedLineUsesHostParentInsteadOfForgedPayloadParent() {
        run(Map.of("order_id","parent-forged"),original());
        verify(mapper).selectByQuery(startsWith("SELECT amount FROM"),eq(Map.of("parentId","parent-real","tenantId",1L)));
        verify(mapper).update(eq("mt_pm_order"),argThat(data -> new BigDecimal("2.50").equals(data.get("total_amount"))),anyMap());
    }
    @Test void emptyDeletePayloadStillResetsTheLastChildTotalToZero() {
        when(mapper.selectByQuery(anyString(),anyMap())).thenReturn(List.of());
        run(Map.of(),original());
        verify(mapper).update(eq("mt_pm_order"),argThat(data -> BigDecimal.ZERO.equals(data.get("total_amount"))),anyMap());
    }
    @Test void missingBeforeSnapshotFailsClosedBeforeAnyAggregateWrite() {
        assertThrows(IllegalStateException.class,()->run(Map.of("order_id","parent-forged"),null));
        verify(mapper,never()).update(anyString(),anyMap(),anyMap());
    }
    @Test void mismatchedTenantOrTargetSnapshotCannotRedirectAnAggregate() {
        var wrongTenant=original();wrongTenant.put("tenant_id",2L);
        assertThrows(IllegalStateException.class,()->run(Map.of(),wrongTenant));
        var wrongTarget=original();wrongTarget.put("pid","another-line");
        assertThrows(IllegalStateException.class,()->run(Map.of(),wrongTarget));
        verify(mapper,never()).update(anyString(),anyMap(),anyMap());
    }
    @Test void nonDeleteStillUsesTheCurrentRowInsteadOfTheOldSnapshot() {
        request.setOperationType("update");
        config=new HashMap<>(config);config.put("type","update");
        when(mapper.selectByQuery(anyString(),anyMap())).thenAnswer(call ->
                ((String)call.getArgument(0)).startsWith("SELECT amount FROM")
                    ? List.of(Map.of("amount",new BigDecimal("2.50")))
                    : List.of(Map.of("pid","line-1","tenant_id",1L,"order_id","parent-current")));
        run(Map.of(),original());
        verify(mapper).selectByQuery(startsWith("SELECT amount FROM"),eq(Map.of("parentId","parent-current","tenantId",1L)));
    }

}
