package com.auraboot.framework.integration;

import com.auraboot.framework.application.TestApplication;

import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 基础集成测试
 * 验证测试环境和基础设施是否正常工作
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Transactional
public class MetaCoreServiceIntegrationTest extends BaseIntegrationTest{
    



    /**
     * 测试1: 验证Spring上下文加载
     */
    @Test
    @Order(1)
    public void test01_verifySpringContextLoads() {
        System.out.println("=== 开始测试: 验证Spring上下文加载 ===");
        
        assertNotNull(applicationContext, "Spring应用上下文不应为空");
        assertTrue(applicationContext.getBeanDefinitionCount() > 0, "应该有Bean定义");
        
        System.out.println("✓ Spring上下文加载成功，Bean数量: " + applicationContext.getBeanDefinitionCount());
    }
    

    
    /**
     * 测试3: 检查可用的服务Bean
     */
    @Test
    @Order(3)
    public void test03_checkAvailableServices() {
        System.out.println("=== 开始测试: 检查可用的服务Bean ===");
        
        String[] beanNames = applicationContext.getBeanDefinitionNames();
        
        System.out.println("可用的Bean列表:");
        for (String beanName : beanNames) {
            if (beanName.toLowerCase().contains("service") || 
                beanName.toLowerCase().contains("controller") ||
                beanName.toLowerCase().contains("dict") ||
                beanName.toLowerCase().contains("meta") ||
                beanName.toLowerCase().contains("rbac")) {
                System.out.println("  - " + beanName);
            }
        }
        
        // 检查关键服务是否存在
        assertTrue(checkServiceExists("dictServiceImpl", "数据字典服务"), "dictServiceImpl should exist");
        assertTrue(checkServiceExists("metaModelServiceImpl", "元数据模型服务"), "metaModelServiceImpl should exist");
        assertTrue(checkServiceExists("dynamicController", "动态控制器"), "dynamicController should exist");
        
        System.out.println("✓ 服务Bean检查完成");
    }
    
    /**
     * 测试4: 验证数据库连接
     */
    @Test
    @Order(4)
    public void test04_verifyDatabaseConnection() {
        System.out.println("=== 开始测试: 验证数据库连接 ===");

        assertTrue(applicationContext.containsBean("dataSource"), "数据源Bean应存在");
        Object dataSourceBean = applicationContext.getBean("dataSource");
        assertNotNull(dataSourceBean, "数据源不应为空");
        assertTrue(dataSourceBean instanceof DataSource, "dataSource应为DataSource类型");

        DataSource dataSource = (DataSource) dataSourceBean;
        try (Connection connection = dataSource.getConnection()) {
            assertNotNull(connection, "数据库连接不应为空");
            assertFalse(connection.isClosed(), "数据库连接应保持打开状态");
        } catch (Exception e) {
            fail("数据库连接检查失败: " + e.getMessage());
        }

        System.out.println("✓ 数据库连接检查完成");
    }
    
    /**
     * 测试5: 生成平台功能报告
     */
    @Test
    @Order(5)
    public void test05_generatePlatformReport() {
        System.out.println("=== 开始测试: 生成平台功能报告 ===");
        
        System.out.println("\n=== AuraBoot平台功能检查报告 ===");
        
        // 检查核心服务
        System.out.println("\n1. 核心服务检查:");
        assertTrue(checkAndReportService("dictServiceImpl", "数据字典服务"), "dictServiceImpl should exist");
        assertTrue(checkAndReportService("metaModelServiceImpl", "元数据模型服务"), "metaModelServiceImpl should exist");
        assertTrue(checkAndReportService("metaFieldServiceImpl", "元数据字段服务"), "metaFieldServiceImpl should exist");
        assertTrue(checkAndReportService("dynamicController", "动态CRUD控制器"), "dynamicController should exist");

        // 检查权限相关服务
        System.out.println("\n2. 权限系统检查:");
        assertTrue(checkAndReportService("roleServiceImpl", "角色服务"), "roleServiceImpl should exist");
        assertTrue(checkAndReportService("permissionServiceImpl", "权限服务"), "permissionServiceImpl should exist");
        assertTrue(checkAndReportService("userRoleServiceImpl", "用户角色服务"), "userRoleServiceImpl should exist");

        // 检查查询相关服务
        System.out.println("\n3. 查询系统检查:");
        assertTrue(checkAndReportService("namedQueryServiceImpl", "命名查询服务"), "namedQueryServiceImpl should exist");
        assertTrue(checkAndReportService("secureQueryExecutorImpl", "安全查询执行器"), "secureQueryExecutorImpl should exist");

        // 检查菜单相关服务
        System.out.println("\n4. 菜单系统检查:");
        assertTrue(checkAndReportService("menuServiceImpl", "菜单服务"), "menuServiceImpl should exist");
        
        System.out.println("\n=== 报告生成完成 ===");
        System.out.println("\n📋 下一步建议:");
        System.out.println("1. 实现缺失的服务和控制器");
        System.out.println("2. 运行具体的功能测试类");
        System.out.println("3. 根据测试失败信息完善实现");
        System.out.println("4. 逐步完善整个平台功能");
        
        System.out.println("✓ 平台功能报告生成完成");
    }
    
    /**
     * 检查服务是否存在
     */
    private boolean checkServiceExists(String serviceName, String description) {
        boolean exists = applicationContext.containsBean(serviceName);
        if (exists) {
            System.out.println("  ✓ " + description + " (" + serviceName + ") 存在");
        } else {
            System.out.println("  ✗ " + description + " (" + serviceName + ") 不存在");
        }
        return exists;
    }
    
    /**
     * 检查并报告服务状态
     */
    private boolean checkAndReportService(String serviceName, String description) {
        boolean exists = applicationContext.containsBean(serviceName);
        String status = exists ? "✓ 已实现" : "✗ 未实现";
        System.out.println(String.format("  %-20s %s (%s)", description, status, serviceName));
        return exists;
    }
}
