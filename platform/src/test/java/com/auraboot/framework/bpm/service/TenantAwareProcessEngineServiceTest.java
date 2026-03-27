//package com.auraboot.framework.bpm.service;
//
//import com.auraboot.smart.framework.engine.SmartEngine;
//import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
//import com.auraboot.smart.framework.engine.service.command.ProcessCommandService;
//import com.auraboot.smart.framework.engine.service.query.ProcessQueryService;
//import com.auraboot.framework.application.tenant.MetaContext;
//import com.auraboot.framework.base.test.BaseTest;
//import org.junit.jupiter.api.AfterEach;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.springframework.transaction.annotation.Transactional;
//
//import java.util.HashMap;
//import java.util.Map;
//
//import static org.junit.jupiter.api.Assertions.*;
//import static org.mockito.ArgumentMatchers.*;
//import static org.mockito.Mockito.*;
//
///**
// * 租户感知流程引擎服务单元测试
// * 测试租户隔离机制的正确性
// *
// * @author AuraBoot Team
// */
//@Transactional
//class TenantAwareProcessEngineServiceTest extends BaseTest {
//
//    @Mock
//    private SmartEngine smartEngine;
//
//    @Mock
//    private ProcessCommandService processCommandService;
//
//    @Mock
//    private ProcessQueryService processQueryService;
//
//    @Mock
//    private ProcessInstance processInstance;
//
//    private TenantAwareProcessEngineService tenantAwareProcessEngineService;
//
//    private static final Long TEST_TENANT_ID_LONG = 12345L;
//    private static final String TEST_TENANT_ID = "12345";
//    private static final String TEST_PROCESS_KEY = "test-process";
//    private static final String TEST_BUSINESS_KEY = "test-business-001";
//    private static final String TEST_PROCESS_INSTANCE_ID = "test-instance-001";
//
//    @BeforeEach
//    void setUp() {
//        // Setup SmartEngine mocks
//        lenient().when(smartEngine.getProcessCommandService()).thenReturn(processCommandService);
//        lenient().when(smartEngine.getProcessQueryService()).thenReturn(processQueryService);
//
//        tenantAwareProcessEngineService = new TenantAwareProcessEngineService(smartEngine);
//
//        // 设置租户上下文 - 使用Long类型
//        MetaContext.setTenantId(TEST_TENANT_ID_LONG);
//    }
//
//    @AfterEach
//    void tearDown() {
//        // 清理租户上下文
//        MetaContext.clear();
//    }
//
//    @Test
//    void testStartProcessWithTenantContext() {
//        // 准备测试数据
//        Map<String, Object> variables = new HashMap<>();
//        variables.put("testVar", "testValue");
//
//        when(processInstance.getInstanceId()).thenReturn(TEST_PROCESS_INSTANCE_ID);
//        when(processCommandService.start(anyString(), isNull(), anyString()))
//                .thenReturn(processInstance);
//
//        // 执行测试
//        ProcessInstance result = tenantAwareProcessEngineService.startProcess(
//                TEST_PROCESS_KEY, TEST_BUSINESS_KEY, variables);
//
//        // 验证结果
//        assertNotNull(result, "Process instance should be returned");
//        assertEquals(TEST_PROCESS_INSTANCE_ID, result.getInstanceId(), "Process instance ID should match");
//
//        // 验证ProcessCommandService调用 - 使用正确的参数顺序
//        verify(processCommandService).start(eq(TEST_PROCESS_KEY), isNull(), eq(TEST_TENANT_ID));
//    }
//
//    @Test
//    void testStartProcessWithoutTenantContext() {
//        // 清除租户上下文
//        MetaContext.clear();
//
//        // 执行测试并验证异常
//        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
//            tenantAwareProcessEngineService.startProcess(TEST_PROCESS_KEY, TEST_BUSINESS_KEY, null);
//        });
//
//        assertEquals("Tenant context is required for process operations", exception.getMessage());
//
//        // 验证SmartEngine没有被调用
//        verify(processCommandService, never()).start(anyString(), anyString(), anyString());
//    }
//
//    @Test
//    void testGetProcessInstanceWithTenantContext() {
//        when(processQueryService.findById(anyString(), anyString()))
//                .thenReturn(processInstance);
//
//        // 执行测试
//        ProcessInstance result = tenantAwareProcessEngineService.getProcessInstance(TEST_PROCESS_INSTANCE_ID);
//
//        // 验证结果
//        assertNotNull(result, "Process instance should be returned");
//
//        // 验证ProcessQueryService调用
//        verify(processQueryService).findById(eq(TEST_PROCESS_INSTANCE_ID), eq(TEST_TENANT_ID));
//    }
//
//    @Test
//    void testGetProcessInstanceWithoutTenantContext() {
//        // 清除租户上下文
//        MetaContext.clear();
//
//        // 执行测试并验证异常
//        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
//            tenantAwareProcessEngineService.getProcessInstance(TEST_PROCESS_INSTANCE_ID);
//        });
//
//        assertEquals("Tenant context is required for process operations", exception.getMessage());
//
//        // 验证SmartEngine没有被调用
//        verify(processQueryService, never()).findById(anyString(), anyString());
//    }
//
//    @Test
//    void testAbortProcessWithTenantContext() {
//        String reason = "Test abort";
//
//        // 执行测试
//        assertDoesNotThrow(() -> {
//            tenantAwareProcessEngineService.abortProcess(TEST_PROCESS_INSTANCE_ID, reason);
//        });
//
//        // 验证ProcessCommandService调用
//        verify(processCommandService).abort(eq(TEST_PROCESS_INSTANCE_ID), eq(reason), eq(TEST_TENANT_ID));
//    }
//
//    @Test
//    void testAbortProcessWithoutTenantContext() {
//        // 清除租户上下文
//        MetaContext.clear();
//
//        // 执行测试并验证异常
//        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
//            tenantAwareProcessEngineService.abortProcess(TEST_PROCESS_INSTANCE_ID, "test");
//        });
//
//        assertEquals("Tenant context is required for process operations", exception.getMessage());
//
//        // 验证SmartEngine没有被调用
//        verify(processCommandService, never()).abort(anyString(), anyString(), anyString());
//    }
//}