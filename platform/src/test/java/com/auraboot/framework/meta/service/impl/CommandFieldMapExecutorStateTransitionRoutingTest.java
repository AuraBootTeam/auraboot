package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.DataDomainService;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import com.auraboot.framework.meta.service.MetaModelService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression: a state_transition command invoked via raw API with operationType 'EXECUTE'
 * used to fall through to the INSERT branch (resolveOperationType let the client value win
 * over the configured type), silently creating a partial blank row instead of updating the
 * target record. The configured type must always route state transitions to UPDATE.
 */
@ExtendWith(MockitoExtension.class)
class CommandFieldMapExecutorStateTransitionRoutingTest {

    @Mock
    private DynamicDataMapper dynamicDataMapper;

    @Mock
    private MetaModelService metaModelService;

    @Mock
    private DataPermissionEngine dataPermissionEngine;

    @Mock
    private DataDomainService dataDomainService;

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("state_transition with client operationType EXECUTE routes to UPDATE, never INSERT")
    void stateTransitionWithExecuteOperationTypeRoutesToUpdate() {
        when(metaModelService.getModelDefinition("inv_stock_check")).thenReturn(Optional.of(stockCheckModel()));
        when(dynamicDataMapper.findJsonbColumns("mt_inv_stock_check")).thenReturn(Set.of());
        when(dynamicDataMapper.updateByQuery(anyString(), anyMap())).thenReturn(1);

        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setOperationType("EXECUTE");
        request.setTargetRecordPid("SC-001");

        executor().executeImplicitFieldMapPhase(
                Map.of("type", "state_transition", "stateField", "inv_sc_status", "toState", "pending"),
                Map.of(),
                100L,
                request,
                command());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(dynamicDataMapper).updateByQuery(sqlCaptor.capture(), paramsCaptor.capture());
        verify(dynamicDataMapper, never()).insert(anyString(), anyMap());
        assertThat(sqlCaptor.getValue())
                .contains("UPDATE mt_inv_stock_check")
                .contains("inv_sc_status");
        assertThat(paramsCaptor.getValue()).containsEntry("recordId", "SC-001");
    }

    @Test
    @DisplayName("state_transition without targetRecordPid fails fast instead of inserting a blank row")
    void stateTransitionWithoutTargetFailsFast() {
        when(metaModelService.getModelDefinition("inv_stock_check")).thenReturn(Optional.of(stockCheckModel()));
        when(dynamicDataMapper.findJsonbColumns("mt_inv_stock_check")).thenReturn(Set.of());

        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setOperationType("EXECUTE");

        assertThatThrownBy(() -> executor().executeImplicitFieldMapPhase(
                Map.of("type", "state_transition", "stateField", "inv_sc_status", "toState", "pending"),
                Map.of(),
                100L,
                request,
                command()))
                .isInstanceOfSatisfying(BusinessException.class, failure ->
                        assertThat(failure.getResponseCode()).isEqualTo(ResponseCode.BadParam));
        verify(dynamicDataMapper, never()).insert(anyString(), anyMap());
    }

    private CommandFieldMapExecutor executor() {
        return new CommandFieldMapExecutor(
                dynamicDataMapper,
                metaModelService,
                org.mockito.Mockito.mock(com.auraboot.framework.permission.service.FieldPermissionService.class),
                dataPermissionEngine,
                dataDomainService,
                mock(CommandModelMutationSupport.class));
    }

    private CommandDefinition command() {
        CommandDefinition command = new CommandDefinition();
        command.setCode("inv:submit_stock_check");
        command.setModelCode("inv_stock_check");
        return command;
    }

    private ModelDefinition stockCheckModel() {
        return ModelDefinition.builder()
                .code("inv_stock_check")
                .tableName("mt_inv_stock_check")
                .fields(List.of(
                        field("inv_sc_status"),
                        field("inv_sc_date"),
                        field("inv_sc_remark"),
                        field("tenant_id"),
                        field("pid"),
                        field("created_at"),
                        field("updated_at")
                ))
                .build();
    }

    private FieldDefinition field(String code) {
        return FieldDefinition.builder()
                .code(code)
                .columnName(code)
                .dataType("string")
                .build();
    }
}
