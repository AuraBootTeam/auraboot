//package com.auraboot.framework.bpm.service;
//
//import com.auraboot.smart.framework.engine.SmartEngine;
//import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
//import com.auraboot.smart.framework.engine.service.command.ProcessCommandService;
//import com.auraboot.smart.framework.engine.service.param.query.ProcessInstanceQueryParam;
//import com.auraboot.smart.framework.engine.service.query.ProcessQueryService;
//import com.auraboot.smart.framework.engine.service.query.VariableQueryService;
//import com.auraboot.framework.application.tenant.MetaContext;
//import com.auraboot.framework.bpm.audit.BpmAuditService;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.InjectMocks;
//import org.mockito.Mock;
//import org.mockito.junit.jupiter.MockitoExtension;
//import org.springframework.security.core.Authentication;
//import org.springframework.security.core.context.SecurityContext;
//import org.springframework.security.core.context.SecurityContextHolder;
//
//import java.util.HashMap;
//import java.util.List;
//import java.util.Map;
//
//import static org.junit.jupiter.api.Assertions.*;
//import static org.mockito.ArgumentMatchers.*;
//import static org.mockito.Mockito.*;
//
///**
// * ProcessEngineService单元测试
// *
// * @author AuraBoot Team
// */
//@ExtendWith(MockitoExtension.class)
//class ProcessEngineServiceTest {
//
//    @Mock
//    private SmartEngine smartEngine;
//
//    @Mock
//    private BpmAuditService bpmAuditService;
//
//    @Mock
//    private ProcessCommandService processCommandService;
//
//    @Mock
//    private ProcessQueryService processQueryService;
//
//    @Mock
//    private VariableQueryService variableQueryService;
//
//    @Mock
//    private ProcessInstance processInstance;
//
//    @Mock
//    private SecurityContext securityContext;
//
//    @Mock
//    private Authentication authentication;
//
//    @InjectMocks
//    private ProcessEngineService processEngineService;
//
//    private static final String TEST_TENANT_ID = "test-tenant";
//    private static final String TEST_USER_ID = "test-user";
//    private static final String TEST_PROCESS_DEFINITION_ID = "test-process";
//    private static final String TEST_BUSINESS_KEY = "test-business-key";
//    private static final String TEST_PROCESS_INSTANCE_ID = "test-process-instance";
//
//    @BeforeEach
//    void setUp() {
//        // 设置租户上下文
//        MetaContext.setCurrentTenantId(TEST_TENANT_ID);
//
//        // 设置安全上下文
//        SecurityContextHolder.setContext(securityContext);
//        when(securityContext.getAuthentication()).thenReturn(authentication);
//        when(authentication.isAuthenticated()).thenReturn(true);
//        when(authentication.getName()).thenReturn(TEST_USER_ID);
//
//        // 设置SmartEngine mock
//        when(smartEngine.getProcessCommandService()).thenReturn(processCommandService);
//        when(smartEngine.getProcessQueryService()).thenReturn(processQueryService);
//        when(smartEngine.getVariableQueryService()).thenReturn(variableQueryService);
//    }
//
//    @Test
//    void testStartProcess_Success() {
//        // Arrange
//        Map<String, Object> variables = new HashMap<>();
//        variables.put("testKey", "testValue");
//
//        when(processInstance.getId()).thenReturn(TEST_PROCESS_INSTANCE_ID);
//        when(processCommandService.start(anyString(), anyString(), any(Map.class))).thenReturn(processInstance);
//
//        // Act
//        ProcessInstance result = processEngineService.startProcess(
//                TEST_PROCESS_DEFINITION_ID, TEST_BUSINESS_KEY, variables);
//
//        // Assert
//        assertNotNull(result);
//        assertEquals(TEST_PROCESS_INSTANCE_ID, result.getId());
//
//        // 验证参数设置
//        verify(processCommandService).start(eq(TEST_PROCESS_DEFINITION_ID), eq(TEST_BUSINESS_KEY), argThat(requestVars -> {
//            assertEquals(TEST_TENANT_ID, requestVars.get("tenantId"));
//            assertEquals(TEST_USER_ID, requestVars.get("startUserId"));
//            assertEquals("testValue", requestVars.get("testKey"));
//            return true;
//        }));
//
//        // 验证审计日志
//        verify(bpmAuditService).recordProcessStart(
//                TEST_PROCESS_INSTANCE_ID, TEST_PROCESS_DEFINITION_ID,
//                TEST_BUSINESS_KEY, TEST_USER_ID, TEST_TENANT_ID);
//    }
//
//    @Test
//    void testStartProcess_WithNullVariables() {
//        // Arrange
//        when(processInstance.getId()).thenReturn(TEST_PROCESS_INSTANCE_ID);
//        when(processCommandService.start(anyString(), anyString(), any(Map.class))).thenReturn(processInstance);
//
//        // Act
//        ProcessInstance result = processEngineService.startProcess(
//                TEST_PROCESS_DEFINITION_ID, TEST_BUSINESS_KEY, null);
//
//        // Assert
//        assertNotNull(result);
//
//        // 验证变量仍然包含租户和用户信息
//        verify(processCommandService).start(eq(TEST_PROCESS_DEFINITION_ID), eq(TEST_BUSINESS_KEY), argThat(requestVars -> {
//            assertEquals(TEST_TENANT_ID, requestVars.get("tenantId"));
//            assertEquals(TEST_USER_ID, requestVars.get("startUserId"));
//            return true;
//        }));
//    }
//
//    @Test
//    void testGetProcessInstance_Success() {
//        // Arrange
//        Map<String, Object> variables = new HashMap<>();
//        variables.put("tenantId", TEST_TENANT_ID);
//
//        when(processInstance.getId()).thenReturn(TEST_PROCESS_INSTANCE_ID);
//        when(processQueryService.findProcessInstances(any(ProcessInstanceQueryParam.class)))
//                .thenReturn(List.of(processInstance));
//        when(variableQueryService.findVariables(TEST_PROCESS_INSTANCE_ID))
//                .thenReturn(variables);
//
//        // Act
//        ProcessInstance result = processEngineService.getProcessInstance(TEST_PROCESS_INSTANCE_ID);
//
//        // Assert
//        assertNotNull(result);
//        assertEquals(TEST_PROCESS_INSTANCE_ID, result.getId());
//
//        verify(processQueryService).findProcessInstances(argThat(param ->
//                TEST_PROCESS_INSTANCE_ID.equals(param.getProcessInstanceId())));
//    }
//
//    @Test
//    void testGetProcessInstance_NotFound() {
//        // Arrange
//        when(processQueryService.findProcessInstances(any(ProcessInstanceQueryParam.class)))
//                .thenReturn(List.of());
//
//        // Act
//        ProcessInstance result = processEngineService.getProcessInstance(TEST_PROCESS_INSTANCE_ID);
//
//        // Assert
//        assertNull(result);
//    }
//
//    @Test
//    void testGetProcessInstance_TenantAccessDenied() {
//        // Arrange
//        Map<String, Object> variables = new HashMap<>();
//        variables.put("tenantId", "other-tenant"); // 不同的租户ID
//
//        when(processInstance.getId()).thenReturn(TEST_PROCESS_INSTANCE_ID);
//        when(processQueryService.findProcessInstances(any(ProcessInstanceQueryParam.class)))
//                .thenReturn(List.of(processInstance));
//        when(variableQueryService.findVariables(TEST_PROCESS_INSTANCE_ID))
//                .thenReturn(variables);
//
//        // Act
//        ProcessInstance result = processEngineService.getProcessInstance(TEST_PROCESS_INSTANCE_ID);
//
//        // Assert
//        assertNull(result);
//    }
//
//    @Test
//    void testSuspendProcessInstance_Success() {
//        // Arrange
//        Map<String, Object> variables = new HashMap<>();
//        variables.put("tenantId", TEST_TENANT_ID);
//
//        when(processInstance.getId()).thenReturn(TEST_PROCESS_INSTANCE_ID);
//        when(processQueryService.findProcessInstances(any(ProcessInstanceQueryParam.class)))
//                .thenReturn(List.of(processInstance));
//        when(variableQueryService.findVariables(TEST_PROCESS_INSTANCE_ID))
//                .thenReturn(variables);
//
//        // Act
//        processEngineService.suspendProcessInstance(TEST_PROCESS_INSTANCE_ID);
//
//        // Assert
//        verify(processCommandService).suspend(TEST_PROCESS_INSTANCE_ID);
//        verify(bpmAuditService).recordProcessSuspend(TEST_PROCESS_INSTANCE_ID, TEST_USER_ID, TEST_TENANT_ID);
//    }
//
//    @Test
//    void testSuspendProcessInstance_NotFound() {
//        // Arrange
//        when(processQueryService.findProcessInstances(any(ProcessInstanceQueryParam.class)))
//                .thenReturn(List.of());
//
//        // Act & Assert
//        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
//                processEngineService.suspendProcessInstance(TEST_PROCESS_INSTANCE_ID));
//
//        assertTrue(exception.getMessage().contains("Process instance not found or access denied"));
//        verify(processCommandService, never()).suspend(anyString());
//    }
//
//    @Test
//    void testResumeProcessInstance_Success() {
//        // Arrange
//        Map<String, Object> variables = new HashMap<>();
//        variables.put("tenantId", TEST_TENANT_ID);
//
//        when(processInstance.getId()).thenReturn(TEST_PROCESS_INSTANCE_ID);
//        when(processQueryService.findProcessInstances(any(ProcessInstanceQueryParam.class)))
//                .thenReturn(List.of(processInstance));
//        when(variableQueryService.findVariables(TEST_PROCESS_INSTANCE_ID))
//                .thenReturn(variables);
//
//        // Act
//        processEngineService.resumeProcessInstance(TEST_PROCESS_INSTANCE_ID);
//
//        // Assert
//        verify(processCommandService).activate(TEST_PROCESS_INSTANCE_ID);
//        verify(bpmAuditService).recordProcessResume(TEST_PROCESS_INSTANCE_ID, TEST_USER_ID, TEST_TENANT_ID);
//    }
//
//    @Test
//    void testTerminateProcessInstance_Success() {
//        // Arrange
//        String reason = "Test termination";
//        Map<String, Object> variables = new HashMap<>();
//        variables.put("tenantId", TEST_TENANT_ID);
//
//        when(processInstance.getId()).thenReturn(TEST_PROCESS_INSTANCE_ID);
//        when(processQueryService.findProcessInstances(any(ProcessInstanceQueryParam.class)))
//                .thenReturn(List.of(processInstance));
//        when(variableQueryService.findVariables(TEST_PROCESS_INSTANCE_ID))
//                .thenReturn(variables);
//
//        // Act
//        processEngineService.terminateProcessInstance(TEST_PROCESS_INSTANCE_ID, reason);
//
//        // Assert
//        verify(processCommandService).complete(TEST_PROCESS_INSTANCE_ID);
//        verify(bpmAuditService).recordProcessTerminate(TEST_PROCESS_INSTANCE_ID, reason, TEST_USER_ID, TEST_TENANT_ID);
//    }
//
//    @Test
//    void testGetProcessInstancesByUser_Success() {
//        // Arrange
//        Map<String, Object> variables = new HashMap<>();
//        variables.put("tenantId", TEST_TENANT_ID);
//
//        when(processInstance.getId()).thenReturn(TEST_PROCESS_INSTANCE_ID);
//        when(processQueryService.findProcessInstances(any(ProcessInstanceQueryParam.class)))
//                .thenReturn(List.of(processInstance));
//        when(variableQueryService.findVariables(TEST_PROCESS_INSTANCE_ID))
//                .thenReturn(variables);
//
//        // Act
//        List<ProcessInstance> result = processEngineService.getProcessInstancesByUser(TEST_USER_ID, TEST_TENANT_ID);
//
//        // Assert
//        assertNotNull(result);
//        assertEquals(1, result.size());
//        assertEquals(TEST_PROCESS_INSTANCE_ID, result.get(0).getId());
//    }
//
//    @Test
//    void testGetCurrentUserId_WithoutAuthentication() {
//        // Arrange
//        when(securityContext.getAuthentication()).thenReturn(null);
//        when(processCommandService.start(anyString(), anyString(), any(Map.class))).thenReturn(processInstance);
//        when(processInstance.getId()).thenReturn(TEST_PROCESS_INSTANCE_ID);
//
//        // Act
//        ProcessInstance result = processEngineService.startProcess(
//                TEST_PROCESS_DEFINITION_ID, TEST_BUSINESS_KEY, new HashMap<>());
//
//        // Assert
//        verify(processCommandService).start(eq(TEST_PROCESS_DEFINITION_ID), eq(TEST_BUSINESS_KEY), argThat(requestVars -> {
//            assertEquals("system", requestVars.get("startUserId"));
//            return true;
//        }));
//    }
//}