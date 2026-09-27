package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.dto.BindingRuleDTO;
import com.auraboot.framework.meta.dto.CommandDefinitionDTO;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.CommandService;
import com.auraboot.framework.meta.service.impl.CommandMetadataCacheService;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoadPhaseTest {

    private final CommandMetadataCacheService metadata = mock(CommandMetadataCacheService.class);
    private final CommandService commandService = mock(CommandService.class);
    private final LoadPhase phase = new LoadPhase(metadata, commandService, new ObjectMapper());

    @Test
    void releaseCommand_executesWithoutTenantLocalCommandRows() {
        CommandExecuteRequest request = request("source-record-pid");
        CommandPipelineContext context = context("xy:create_gp", request);
        BindingRuleDTO binding = new BindingRuleDTO();
        binding.setRuleType("FIELD_MAP");
        binding.setSourceField("xy_gp_name");
        binding.setTargetField("xy_gp_name");
        binding.setEnabled(true);
        CommandDefinitionDTO command = new CommandDefinitionDTO();
        command.setCode("xy:create_gp");
        command.setModelCode("xy_growth_plan");
        command.setExecutionConfig("{\"type\":\"create\"}");
        command.setStatus(Status.PUBLISHED.getCode());
        command.setVersion(1);
        command.setSemver("1.0.0");
        command.setIsCurrent(true);
        command.setBindingRules(List.of(binding));
        when(commandService.findByCode("xy:create_gp")).thenReturn(command);

        phase.execute(context);

        assertThat(context.getCommand().getCode()).isEqualTo("xy:create_gp");
        assertThat(context.getCommand().getId()).isNull();
        assertThat(context.getRulesByType().get("FIELD_MAP")).hasSize(1);
        assertThat(request.getTargetRecordId()).isNull();
        verify(metadata, never()).findBindingRulesByCommandId(null);
    }

    @Test
    void createCommand_clearsSourceRecordTarget_butKeepsSourcePidInPayload() {
        CommandExecuteRequest request = request("source-defect-pid");
        CommandPipelineContext context = context("quality:create_capa", request);
        stubCommand("quality:create_capa", "{\"type\":\"create\"}");

        phase.execute(context);

        assertThat(request.getTargetRecordId()).isNull();
        assertThat(context.getPayload()).containsEntry("recordPid", "source-defect-pid");
    }

    @Test
    void updateCommand_preservesExistingTarget() {
        CommandExecuteRequest request = request("existing-ticket-pid");
        CommandPipelineContext context = context("ticket:update", request);
        stubCommand("ticket:update", "{\"type\":\"update\"}");

        phase.execute(context);

        assertThat(request.getTargetRecordId()).isEqualTo("existing-ticket-pid");
    }

    @Test
    void requestCreate_clearsPidPropagatedByAPreviousExecution_whenConfigHasNoType() {
        CommandExecuteRequest request = request("created-record-pid");
        request.setOperationType("create");
        CommandPipelineContext context = context("device:create", request);
        stubCommand("device:create", "{}");

        phase.execute(context);

        assertThat(request.getTargetRecordId()).isNull();
    }

    @Test
    void configuredUpdate_winsOverAConflictingRequestCreateHint() {
        CommandExecuteRequest request = request("existing-ticket-pid");
        request.setOperationType("create");
        CommandPipelineContext context = context("ticket:update", request);
        stubCommand("ticket:update", "{\"type\":\"update\"}");

        phase.execute(context);

        assertThat(request.getTargetRecordId()).isEqualTo("existing-ticket-pid");
    }

    private CommandExecuteRequest request(String recordPid) {
        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setTargetRecordId(recordPid);
        request.setPayload(new HashMap<>(Map.of("recordPid", recordPid)));
        return request;
    }

    private CommandPipelineContext context(String commandCode, CommandExecuteRequest request) {
        return CommandPipelineContext.builder()
                .commandCode(commandCode)
                .request(request)
                .tenantId(1L)
                .userId(2L)
                .startTime(System.currentTimeMillis())
                .payload(request.getPayload())
                .build();
    }

    private void stubCommand(String commandCode, String executionConfig) {
        CommandDefinition command = new CommandDefinition();
        command.setId(10L);
        command.setCode(commandCode);
        command.setStatus(Status.PUBLISHED.getCode());
        command.setExecutionConfig(executionConfig);
        when(metadata.findCurrentCommandByCode(commandCode)).thenReturn(command);
        when(metadata.findBindingRulesByCommandId(10L)).thenReturn(List.of());
    }
}
