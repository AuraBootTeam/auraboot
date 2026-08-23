package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.impl.CommandMetadataCacheService;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoadPhaseTest {

    private final CommandMetadataCacheService metadata = mock(CommandMetadataCacheService.class);
    private final LoadPhase phase = new LoadPhase(metadata, new ObjectMapper());

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
