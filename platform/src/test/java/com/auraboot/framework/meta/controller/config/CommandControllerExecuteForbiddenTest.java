package com.auraboot.framework.meta.controller.config;

import com.auraboot.framework.application.web.handler.GlobalExceptionHandler;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.exception.MetaApiExceptionHandler;
import com.auraboot.framework.i18n.service.I18nService;
import com.auraboot.framework.i18n.util.I18nLocaleResolver;
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
import static org.mockito.Mockito.mock;
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
    private I18nService i18nService;
    private I18nLocaleResolver localeResolver;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        GlobalExceptionHandler exceptionHandler = new GlobalExceptionHandler();
        ReflectionTestUtils.setField(exceptionHandler, "activeProfile", "prod");

        i18nService = mock(I18nService.class);
        localeResolver = mock(I18nLocaleResolver.class);
        ReflectionTestUtils.setField(exceptionHandler, "i18nService", i18nService);
        ReflectionTestUtils.setField(exceptionHandler, "i18nLocaleResolver", localeResolver);
        MetaApiExceptionHandler metaHandler = new MetaApiExceptionHandler();
        ReflectionTestUtils.setField(metaHandler, "globalExceptionHandler", exceptionHandler);

        CommandController controller = new CommandController(
                commandService,
                commandExecutor,
                pluginResourceTracker,
                commandAuditLogService);
        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(metaHandler, exceptionHandler)
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

    @Test
    void localizedBusinessRejectionTakesPrecedenceOverSecurityCause() throws Exception {
        when(localeResolver.resolveLocale(any())).thenReturn("zh-CN");
        when(i18nService.getValue("zh-CN", "inventory.error.quality_hold.issue"))
                .thenReturn("质量冻结中，请先解除冻结后再分配领料");
        when(commandExecutor.execute(eq("inv:create_issue_pick_task"), any(CommandExecuteRequest.class)))
                .thenThrow(new BusinessException(ResponseCode.BadParam,
                        "$i18n:inventory.error.quality_hold.issue", new SecurityException("internal hold gate")));
        mockMvc.perform(post("/api/meta/commands/execute/inv:create_issue_pick_task")
                        .accept(MediaType.APPLICATION_JSON).contentType(MediaType.APPLICATION_JSON).content("{\"payload\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.context").value("质量冻结中，请先解除冻结后再分配领料"));
    }

    @Test
    void directSecurityFailureRemainsForbiddenAndDoesNotExposeDetails() throws Exception {
        when(commandExecutor.execute(eq("inventory.read"), any(CommandExecuteRequest.class)))
                .thenThrow(new SecurityException("sensitive row ownership"));
        mockMvc.perform(post("/api/meta/commands/execute/inventory.read")
                        .accept(MediaType.APPLICATION_JSON).contentType(MediaType.APPLICATION_JSON).content("{\"payload\":{}}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.context").value("Access denied"));
    }
}
