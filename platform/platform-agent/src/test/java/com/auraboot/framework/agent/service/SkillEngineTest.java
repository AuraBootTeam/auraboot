package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.SkillInput;
import com.auraboot.framework.agent.dto.SkillResult;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SkillEngine")
class SkillEngineTest {

    @Mock private AgentSkillService agentSkillService;
    @Mock private ToolLoopService toolLoopService;
    @Mock private StepLoopService stepLoopService;
    @Mock private DynamicDataMapper dynamicDataMapper;
    @Mock private DynamicDataService dynamicDataService;

    private SkillEngine skillEngine;

    @BeforeEach
    void setUp() {
        skillEngine = new SkillEngine(
                agentSkillService,
                toolLoopService,
                stepLoopService,
                dynamicDataMapper,
                dynamicDataService,
                new ObjectMapper());
    }

    @Test
    @DisplayName("unknown execution mode fails closed instead of falling back to template")
    void unknownExecutionModeFailsClosedInsteadOfTemplateFallback() {
        when(agentSkillService.loadSkill(1L, "bad-mode")).thenReturn(Map.of(
                "skill_code", "bad-mode",
                "execution_mode", "shell_script",
                "failure_mode", "fail_fast",
                "skill_tools", "[\"tool.one\"]"));

        SkillResult result = skillEngine.execute(
                1L,
                "run-1",
                "bad-mode",
                SkillInput.builder().parameters(Map.of("x", 1)).build(),
                null,
                null,
                null);

        assertThat(result.getStatus()).isEqualTo(SkillResult.Status.FAILED);
        assertThat(result.getErrorMessage())
                .contains("Unsupported skill execution mode")
                .contains("shell_script");
        verify(toolLoopService, never())
                .executeToolCall(anyLong(), anyString(), any(), any(), anyString(), any(), any(), any());
    }
}
