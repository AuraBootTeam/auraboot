package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommandFieldMapExecutorReferencePidCompanionTest {

    @Mock
    private DynamicDataMapper dynamicDataMapper;

    @Mock
    private MetaModelService metaModelService;

    @Test
    @DisplayName("implicit create copies *_pid companion into missing reference *_id field")
    void implicitCreateCopiesPidCompanionIntoReferenceField() {
        when(metaModelService.getModelDefinition("mkt_purchase")).thenReturn(Optional.of(purchaseModel()));
        when(dynamicDataMapper.insert(eq("mt_mkt_purchase"), anyMap())).thenReturn(1);

        CommandFieldMapExecutor executor = new CommandFieldMapExecutor(dynamicDataMapper, metaModelService);
        executor.executeImplicitFieldMapPhase(
                Map.of("type", "create", "inputFields", List.of("mkt_pur_plugin_pid", "mkt_pur_plan_pid")),
                Map.of("mkt_pur_plugin_pid", "PLG-PID", "mkt_pur_plan_pid", "PLAN-PID"),
                100L,
                new CommandExecuteRequest(),
                command());

        ArgumentCaptor<Map<String, Object>> captor = mapCaptor();
        verify(dynamicDataMapper).insert(eq("mt_mkt_purchase"), captor.capture());
        Map<String, Object> row = captor.getValue();

        assertThat(row).containsEntry("mkt_pur_plugin_pid", "PLG-PID");
        assertThat(row).containsEntry("mkt_pur_plugin_id", "PLG-PID");
        assertThat(row).containsEntry("mkt_pur_plan_pid", "PLAN-PID");
        assertThat(row).containsEntry("mkt_pur_plan_id", "PLAN-PID");
    }

    @Test
    @DisplayName("implicit create does not overwrite explicit reference *_id field")
    void implicitCreateDoesNotOverwriteExplicitReferenceId() {
        when(metaModelService.getModelDefinition("mkt_purchase")).thenReturn(Optional.of(purchaseModel()));
        when(dynamicDataMapper.insert(eq("mt_mkt_purchase"), anyMap())).thenReturn(1);

        CommandFieldMapExecutor executor = new CommandFieldMapExecutor(dynamicDataMapper, metaModelService);
        executor.executeImplicitFieldMapPhase(
                Map.of("type", "create", "inputFields", List.of("mkt_pur_plugin_id", "mkt_pur_plugin_pid")),
                Map.of("mkt_pur_plugin_id", "EXPLICIT-REF", "mkt_pur_plugin_pid", "PLG-PID"),
                100L,
                new CommandExecuteRequest(),
                command());

        ArgumentCaptor<Map<String, Object>> captor = mapCaptor();
        verify(dynamicDataMapper).insert(eq("mt_mkt_purchase"), captor.capture());
        Map<String, Object> row = captor.getValue();

        assertThat(row).containsEntry("mkt_pur_plugin_id", "EXPLICIT-REF");
        assertThat(row).containsEntry("mkt_pur_plugin_pid", "PLG-PID");
    }

    private CommandDefinition command() {
        CommandDefinition command = new CommandDefinition();
        command.setCode("mkt:create_purchase");
        command.setModelCode("mkt_purchase");
        return command;
    }

    private ModelDefinition purchaseModel() {
        return ModelDefinition.builder()
                .code("mkt_purchase")
                .tableName("mt_mkt_purchase")
                .fields(List.of(
                        reference("mkt_pur_plugin_id"),
                        string("mkt_pur_plugin_pid"),
                        reference("mkt_pur_plan_id"),
                        string("mkt_pur_plan_pid"),
                        string("tenant_id"),
                        string("pid"),
                        string("created_at"),
                        string("updated_at")
                ))
                .build();
    }

    private FieldDefinition reference(String code) {
        return FieldDefinition.builder()
                .code(code)
                .columnName(code)
                .dataType("reference")
                .build();
    }

    private FieldDefinition string(String code) {
        return FieldDefinition.builder()
                .code(code)
                .columnName(code)
                .dataType("string")
                .build();
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Map<String, Object>> mapCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }
}
