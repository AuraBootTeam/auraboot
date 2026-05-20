package com.auraboot.framework.meta.controller.config;

import com.auraboot.framework.application.web.handler.GlobalExceptionHandler;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.service.CommandAuditLogService;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.meta.service.CommandService;
import com.auraboot.framework.plugin.service.PluginResourceTracker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CommandControllerExecuteForbiddenTest {

    @Mock
    private CommandService commandService;

    @Mock
    private CommandExecutor commandExecutor;

    @Mock
    private PluginResourceTracker pluginResourceTracker;

    @Mock
    private CommandAuditLogService commandAuditLogService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        GlobalExceptionHandler exceptionHandler = new GlobalExceptionHandler();
        ReflectionTestUtils.setField(exceptionHandler, "activeProfile", "prod");

        CommandController controller = new CommandController(
                commandService,
                commandExecutor,
                pluginResourceTracker,
                commandAuditLogService);
        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(exceptionHandler)
                .build();
    }

    @Test
    void executeReturnsForbiddenShapeWhenCommandAuthorizationRejects() throws Exception {
        when(commandExecutor.execute(eq("dashboard.export"), any(CommandExecuteRequest.class)))
                .thenThrow(new BusinessException(
                        ResponseCode.FORBIDDEN,
                        "Command permission denied: required one of dashboard.manage"));

        mockMvc.perform(post("/api/meta/commands/execute/dashboard.export")
                        .accept(MediaType.APPLICATION_JSON)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "payload", Map.of("name", "export"),
                                "auditContext", Map.of(
                                        "source", "unified-designer-runtime-preview",
                                        "permissionCode", "dashboard.manage")))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ResponseCode.FORBIDDEN.getCode()))
                .andExpect(jsonPath("$.message").value(ResponseCode.FORBIDDEN.getDesc()))
                .andExpect(jsonPath("$.context")
                        .value("Command permission denied: required one of dashboard.manage"));
    }
}
