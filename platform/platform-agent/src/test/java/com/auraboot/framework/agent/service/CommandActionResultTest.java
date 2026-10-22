package com.auraboot.framework.agent.service;

import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommandActionResultTest {
    @Mock private ActionRecorder actionRecorder;
    @Mock private DynamicDataMapper dynamicDataMapper;
    @Mock private CommandExecutor commandExecutor;
    private ToolLoopService service;

    @BeforeEach
    void setUp() {
        service = new ToolLoopService(actionRecorder, null, null, null,
                dynamicDataMapper, commandExecutor, null, new ObjectMapper(), null, null, null);
    }

    @Test
    void createdRecordReferenceLoadsTheCommittedSnapshotForActionEvidence() {
        var result = CommandExecuteResult.builder().data(Map.of(
                "record", Map.of("pid", "created-order"), "id", 123L)).build();
        when(commandExecutor.execute(eq("e2et:create_order"), any())).thenReturn(result);
        when(dynamicDataMapper.selectByQuery(anyString(), anyMap()))
                .thenReturn(List.of(Map.of("model_code", "e2et_order")));
        Map<String, Object> snapshot = Map.of("pid", "created-order", "status", "draft");
        when(actionRecorder.readRecordByPid("e2et_order", "created-order")).thenReturn(snapshot);
        ReflectionTestUtils.invokeMethod(service, "executeDslCommandWithAction",
                "e2et:create_order", Map.of("title", "New order"), 1L, "run-created", null);
        verify(actionRecorder).recordAction(eq(1L), eq("run-created"), eq("e2et:create_order"),
                isNull(), anyMap(), eq(result), isNull(), eq(snapshot), isNull());
        verify(actionRecorder, never()).readRecordByPid("e2et_order", "123");
    }

    @Test
    void commandRecordReferenceUsesEffectiveTargetAndHandlesResultShapes() {
        assertThat(ActionRecorder.commandRecordPid(CommandExecuteResult.builder().data(Map.of(
                "recordPid", "effective", "pid", "legacy", "record", Map.of("pid", "nested"))).build()))
                .isEqualTo("effective");
        assertThat(ActionRecorder.commandRecordPid(CommandExecuteResult.builder().data(Map.of(
                "record", Map.of("pid", "nested"), "id", 123L)).build())).isEqualTo("nested");
        assertThat(ActionRecorder.commandRecordPid(CommandExecuteResult.builder().data(Map.of("pid", "legacy")).build()))
                .isEqualTo("legacy");
        assertThat(ActionRecorder.commandRecordPid(CommandExecuteResult.builder().data(Map.of("id", 123L)).build()))
                .isEqualTo("123");
        assertThat(ActionRecorder.commandRecordPid(CommandExecuteResult.builder().data(Map.of("record", "invalid")).build()))
                .isNull();
        assertThat(ActionRecorder.commandRecordPid(null)).isNull();
    }

}
