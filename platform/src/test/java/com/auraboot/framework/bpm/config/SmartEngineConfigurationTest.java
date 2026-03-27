package com.auraboot.framework.bpm.config;

import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.configuration.ProcessEngineConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SmartEngine配置单元测试
 * 测试SmartEngine初始化和配置加载
 * 
 * @author AuraBoot Team
 */
@SpringBootTest(classes = {
    SmartEngineConfiguration.class
})
@ActiveProfiles("test")
class SmartEngineConfigurationTest {

    @Autowired
    private ProcessEngineConfiguration processEngineConfiguration;

    @Autowired
    private SmartEngine smartEngine;

    @Test
    void testProcessEngineConfigurationCreation() {
        assertNotNull(processEngineConfiguration, "ProcessEngineConfiguration should be created");
        
        // 验证配置对象的基本属性
        assertNotNull(processEngineConfiguration.getIdGenerator(), "IdGenerator should be configured");
        assertNotNull(processEngineConfiguration.getInstanceAccessor(), "InstanceAccessor should be configured");
        assertNotNull(processEngineConfiguration.getExpressionEvaluator(), "ExpressionEvaluator should be configured");
        assertNotNull(processEngineConfiguration.getDelegationExecutor(), "DelegationExecutor should be configured");
    }

    @Test
    void testSmartEngineCreation() {
        assertNotNull(smartEngine, "SmartEngine should be created");
        
        // 验证SmartEngine是否正确初始化
        assertNotNull(smartEngine.getProcessEngineConfiguration(), "SmartEngine should have configuration");
        assertEquals(processEngineConfiguration, smartEngine.getProcessEngineConfiguration(), 
                "SmartEngine should use the same configuration");
    }

    @Test
    void testSmartEngineServices() {
        // 验证SmartEngine的核心服务是否可用
        assertNotNull(smartEngine.getTaskCommandService(), "TaskCommandService should be available");
        assertNotNull(smartEngine.getTaskQueryService(), "TaskQueryService should be available");
        
        // 验证其他核心服务
        assertNotNull(smartEngine.getProcessCommandService(), "ProcessCommandService should be available");
        assertNotNull(smartEngine.getProcessQueryService(), "ProcessQueryService should be available");
    }
}