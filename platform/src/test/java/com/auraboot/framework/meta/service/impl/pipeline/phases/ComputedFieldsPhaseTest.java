package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.meta.service.impl.*;
import com.auraboot.framework.meta.service.impl.pipeline.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Execute real SpEL with JSON decimal inputs and verify the guarded persistence boundary. */
class ComputedFieldsPhaseTest {
    final MetaModelService metadata = mock(MetaModelService.class);
    final DynamicDataMapper mapper = mock(DynamicDataMapper.class);
    final RecordSnapshotReader snapshots = mock(RecordSnapshotReader.class);
    final ComputedFieldsPhase phase = new ComputedFieldsPhase(metadata, new CommandSpelEvaluator(), mapper,
        mock(ChangeTracker.class), snapshots, new CommandFieldMapExecutor(mapper, metadata, null, null, null));
    ModelDefinition model(boolean owned) {
        var fields = List.of(FieldDefinition.builder().code("qty").dataType("decimal").build(),
            FieldDefinition.builder().code("price").dataType("decimal").build(),
            FieldDefinition.builder().code("amount").dataType("decimal").build(),
            FieldDefinition.builder().code("managed").dataType("boolean").immutable(true).build());
        return ModelDefinition.builder().code("line").tableName("mt_line").fields(fields)
            .extension(owned ? Map.of("recordCommandWriters", Map.of("field", "managed", "commands",
                Map.of("create",List.of(),"update",List.of("app:save"),"delete",List.of()))) : Map.of()).build();
    }
    CommandPipelineContext context(String expression) {
        var command = new CommandDefinition(); command.setCode("app:save"); command.setModelCode("line");
        return CommandPipelineContext.builder().command(command).tenantId(1L).userId(7L)
            .request(new CommandExecuteRequest()).fieldMapResults(new HashMap<>(Map.of("recordPid","line-a")))
            .payload(new HashMap<>(Map.of("qty","10","price","1.123456","amount","9999")))
            .execConfig(Map.of("computedFields",Map.of("amount",expression))).build();
    }
    void setup(boolean owned) {
        MetaContext.setContext(1L,7L,null,"system");
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        when(metadata.getModelDefinition("line")).thenReturn(Optional.of(model(owned)));
        when(metadata.getTableName("line")).thenReturn("mt_line");
        when(mapper.selectByQuery(anyString(),anyMap())).thenReturn(List.of(Map.of("id",7L)));
    }
    @AfterEach void clear() { MetaContext.clear(); org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false); }
    @Test void decimalStringsProduceExactPersistedAmountInsteadOfSilentNull() {
        setup(false); when(mapper.update(anyString(),anyMap(),anyMap())).thenReturn(1);
        var ctx=context("qty * price"); phase.execute(ctx);
        verify(mapper).update(eq("mt_line"),argThat(values -> new BigDecimal("11.234560").compareTo((BigDecimal)values.get("amount"))==0),eq(Map.of("tenant_id",1L,"id",7L)));
        assertThat((BigDecimal)ctx.getPayload().get("amount")).isEqualByComparingTo("11.23456");
    }
    @Test void failedExpressionRejectsTheCommandBeforeComputedWrite() {
        setup(false);
        assertThatThrownBy(() -> phase.execute(context("unknown * qty"))).hasMessageContaining("COMPUTED_FIELD_FAILED");
        verify(mapper,never()).update(anyString(),anyMap(),anyMap());
    }
    @Test void computedFieldsCannotBypassManagedRecordOwnership() {
        setup(true);
        when(mapper.selectRecordWriterTargetsForUpdate(anyString(),anyString(),anyString(),anyMap()))
            .thenReturn(List.of(Map.of("id",7L,"pid","line-a","managed",true)));
        assertThatThrownBy(() -> phase.execute(context("qty * price"))).hasMessageContaining("RECORD_WRITER_DENIED");
        verify(mapper,never()).update(anyString(),anyMap(),anyMap());
    }
    @Test void vanishedComputedTargetDoesNotReportSuccess() {
        setup(false);
        assertThatThrownBy(() -> phase.execute(context("qty * price"))).hasMessageContaining("target is missing or changed");
    }
}
