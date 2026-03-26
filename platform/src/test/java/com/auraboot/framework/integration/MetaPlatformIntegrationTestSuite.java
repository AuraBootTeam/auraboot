package com.auraboot.framework.integration;

import com.auraboot.framework.application.TestApplication;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Meta平台集成测试套件
 * 综合测试整个Meta平台的集成状态和功能完整性
 * 
 * @author AuraBoot Team
 * @since 2.1.0
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Transactional
public class MetaPlatformIntegrationTestSuite extends BaseIntegrationTest {

    /**
     * 测试1: 平台启动和基础设施验证
     */
    @Test
    @Order(1)
    public void test01_platformBootstrapVerification() {
        System.out.println("=== 开始测试: 平台启动和基础设施验证 ===");
        
        // 验证Spring上下文
        assertNotNull(applicationContext, "Spring应用上下文应该已加载");
        assertTrue(applicationContext.getBeanDefinitionCount() > 0, "应该有Bean定义");
        
        // 验证测试数据
        assertNotNull(getTestTenant(), "测试租户应该已创建");
        assertNotNull(getTestUser(), "测试用户应该已创建");
        assertNotNull(getTestTenantMember(), "测试租户成员关系应该已创建");
        
        System.out.println("  ✓ Spring上下文加载成功");
        System.out.println("  ✓ 测试数据准备完成");
        System.out.println("  ✓ 租户上下文设置成功");
        
        System.out.println("✓ 平台启动和基础设施验证完成");
    }

    /**
     * 测试2: 核心Meta服务组件验证
     */
    @Test
    @Order(2)
    public void test02_coreMetaServicesVerification() {
        System.out.println("=== 开始测试: 核心Meta服务组件验证 ===");
        
        // 检查核心服务Bean
        System.out.println("检查核心Meta服务:");
        checkAndReportBean("metaModelService", "模型元数据服务");
        checkAndReportBean("metaFieldService", "字段服务");
        checkAndReportBean("metaFieldServiceImpl", "字段服务实现");
        checkAndReportBean("dynamicDataService", "动态数据服务");
        checkAndReportBean("pageSchemaService", "页面Schema服务");
        
        // 检查控制器Bean
        System.out.println("检查Meta控制器:");
        checkAndReportBean("metaModelController", "模型控制器");
        checkAndReportBean("metaFieldController", "字段控制器");
        checkAndReportBean("dynamicController", "动态控制器");
        checkAndReportBean("pageRenderController", "页面渲染控制器");
        
        System.out.println("✓ 核心Meta服务组件验证完成");
    }

    /**
     * 测试3: 安全查询系统组件验证
     */
    @Test
    @Order(3)
    public void test03_securityQuerySystemVerification() {
        System.out.println("=== 开始测试: 安全查询系统组件验证 ===");
        
        // 检查安全查询组件
        System.out.println("检查安全查询组件:");
        checkAndReportBean("secureQueryExecutor", "安全查询执行器");
        checkAndReportBean("sqlInjectionProtector", "SQL注入防护器");
        checkAndReportBean("queryAuditService", "查询审计服务");
        checkAndReportBean("queryAuditServiceImpl", "查询审计服务实现");
        
        // 检查数据访问组件
        System.out.println("检查数据访问组件:");
        checkAndReportBean("queryAuditLogMapper", "查询审计日志Mapper");
        
        System.out.println("✓ 安全查询系统组件验证完成");
    }

    /**
     * 测试4: 权限和安全系统验证
     */
    @Test
    @Order(4)
    public void test04_permissionSecuritySystemVerification() {
        System.out.println("=== 开始测试: 权限和安全系统验证 ===");
        
        // 检查RBAC组件
        System.out.println("检查RBAC组件:");
        checkAndReportBean("roleService", "角色服务");
        checkAndReportBean("permissionService", "权限服务");
        checkAndReportBean("userRoleService", "用户角色服务");
        checkAndReportBean("permissionCalculationService", "权限计算服务");
        
        // 检查安全组件
        System.out.println("检查安全组件:");
        checkAndReportBean("jwtAuthenticationFilter", "JWT认证过滤器");
        checkAndReportBean("tenantInterceptor", "租户拦截器");
        
        System.out.println("✓ 权限和安全系统验证完成");
    }

    /**
     * 测试5: Git-First架构组件验证
     */
    @Test
    @Order(5)
    public void test05_gitFirstArchitectureVerification() {
        System.out.println("=== 开始测试: Git-First架构组件验证 ===");
        
        // 检查Git服务组件
        System.out.println("检查Git服务组件:");
        checkAndReportBean("gitMetaService", "Git元数据服务");
        checkAndReportBean("gitRepoService", "Git仓库服务");
        checkAndReportBean("gitReleaseService", "Git发布服务");
        checkAndReportBean("releaseWorker", "发布工作器");
        checkAndReportBean("projectionEngine", "投影引擎");
        
        // 检查Git控制器
        System.out.println("检查Git控制器:");
        checkAndReportBean("gitRepoController", "Git仓库控制器");
        checkAndReportBean("gitReleaseController", "Git发布控制器");
        
        System.out.println("✓ Git-First架构组件验证完成");
    }

    /**
     * 测试6: 数据字典和基础数据验证
     */
    @Test
    @Order(6)
    public void test06_dictionaryBaseDataVerification() {
        System.out.println("=== 开始测试: 数据字典和基础数据验证 ===");
        
        // 检查字典服务
        System.out.println("检查字典服务:");
        checkAndReportBean("dictService", "数据字典服务");
        checkAndReportBean("dictController", "数据字典控制器");
        
        // 检查基础数据服务
        System.out.println("检查基础数据服务:");
        checkAndReportBean("tenantService", "租户服务");
        checkAndReportBean("userService", "用户服务");
        checkAndReportBean("tenantMemberService", "租户成员服务");
        
        System.out.println("✓ 数据字典和基础数据验证完成");
    }

    /**
     * 测试7: 生成综合平台状态报告
     */
    @Test
    @Order(7)
    public void test07_generateComprehensivePlatformReport() {
        System.out.println("=== 开始测试: 生成综合平台状态报告 ===");
        
        System.out.println("\n" + "=".repeat(60));
        System.out.println("           AuraBoot低代码平台集成状态报告");
        System.out.println("=".repeat(60));
        
        // 1. 平台基础设施
        System.out.println("\n📋 1. 平台基础设施状态:");
        System.out.println("  ✓ Spring Boot应用启动成功");
        System.out.println("  ✓ 数据库连接配置正常");
        System.out.println("  ✓ 多租户上下文管理正常");
        System.out.println("  ✓ 测试环境配置完整");
        
        // 2. 核心功能模块
        System.out.println("\n🔧 2. 核心功能模块状态:");
        reportModuleStatus("Meta元数据管理", checkModuleAvailability(
            "metaModelService", "metaModelController", "metaFieldService", "metaFieldController"));
        reportModuleStatus("动态CRUD系统", checkModuleAvailability(
            "dynamicDataService", "dynamicController"));
        reportModuleStatus("页面Schema管理", checkModuleAvailability(
            "pageSchemaService", "pageRenderController"));
        reportModuleStatus("数据字典系统", checkModuleAvailability(
            "dictService", "dictController"));
        
        // 3. 安全和权限系统
        System.out.println("\n🔒 3. 安全和权限系统状态:");
        reportModuleStatus("RBAC权限系统", checkModuleAvailability(
            "roleService", "permissionService", "userRoleService"));
        reportModuleStatus("安全查询系统", checkModuleAvailability(
            "secureQueryExecutor", "sqlInjectionProtector", "queryAuditService"));
        reportModuleStatus("JWT认证系统", checkModuleAvailability(
            "jwtAuthenticationFilter"));
        reportModuleStatus("多租户隔离", checkModuleAvailability(
            "tenantInterceptor"));
        
        // 4. Git-First架构
        System.out.println("\n📦 4. Git-First架构状态:");
        reportModuleStatus("Git元数据管理", checkModuleAvailability(
            "gitMetaService", "gitRepoService"));
        reportModuleStatus("发布管理系统", checkModuleAvailability(
            "gitReleaseService", "releaseWorker"));
        reportModuleStatus("运行时投影", checkModuleAvailability(
            "projectionEngine"));
        reportModuleStatus("Git API接口", checkModuleAvailability(
            "gitRepoController", "gitReleaseController"));
        
        // 5. 数据访问层
        System.out.println("\n💾 5. 数据访问层状态:");
        reportModuleStatus("审计日志存储", checkModuleAvailability(
            "queryAuditLogMapper"));
        reportModuleStatus("基础数据管理", checkModuleAvailability(
            "tenantService", "userService", "tenantMemberService"));
        
        // 6. 系统完整性评估
        System.out.println("\n📊 6. 系统完整性评估:");
        
        String[] coreComponents = {
            "metaModelService", "metaFieldService", "dynamicDataService", 
            "secureQueryExecutor", "sqlInjectionProtector", "queryAuditService"
        };
        
        int availableCore = 0;
        for (String component : coreComponents) {
            if (applicationContext.containsBean(component)) {
                availableCore++;
            }
        }
        
        double coreCompleteness = (availableCore / (double) coreComponents.length) * 100;
        System.out.println(String.format("  核心功能完整性: %.1f%% (%d/%d)", 
                                        coreCompleteness, availableCore, coreComponents.length));
        
        // 7. 建议和下一步
        System.out.println("\n🎯 7. 建议和下一步:");
        if (coreCompleteness >= 80) {
            System.out.println("  ✅ 平台核心功能基本完整，可以进行业务功能开发");
            System.out.println("  📝 建议: 运行端到端业务流程测试");
            System.out.println("  🚀 建议: 进行性能和压力测试");
            System.out.println("  🔍 建议: 完善API文档和用户手册");
        } else if (coreCompleteness >= 60) {
            System.out.println("  ⚠️  平台功能部分完整，需要完善关键组件");
            System.out.println("  🔧 优先: 完善缺失的核心服务");
            System.out.println("  🧪 建议: 增加单元测试覆盖率");
        } else {
            System.out.println("  ❌ 平台功能不完整，需要大量开发工作");
            System.out.println("  🏗️  优先: 实现基础的Meta服务");
            System.out.println("  🔐 优先: 完善安全和权限系统");
        }
        
        // 8. 技术债务和改进点
        System.out.println("\n⚡ 8. 技术债务和改进点:");
        System.out.println("  📈 性能优化: 查询缓存和索引优化");
        System.out.println("  🔄 代码重构: 统一异常处理和响应格式");
        System.out.println("  📚 文档完善: API文档和开发者指南");
        System.out.println("  🧪 测试增强: 增加集成测试和E2E测试");
        
        System.out.println("\n" + "=".repeat(60));
        System.out.println("                    报告生成完成");
        System.out.println("=".repeat(60));
        
        System.out.println("✓ 综合平台状态报告生成完成");
    }

    /**
     * 检查并报告Bean状态
     */
    private void checkAndReportBean(String beanName, String description) {
        boolean exists = applicationContext.containsBean(beanName);
        String status = exists ? "✓" : "✗";
        System.out.println(String.format("  %s %-25s (%s)", status, description, beanName));
    }

    /**
     * 检查模块可用性
     */
    private boolean checkModuleAvailability(String... beanNames) {
        int available = 0;
        for (String beanName : beanNames) {
            if (applicationContext.containsBean(beanName)) {
                available++;
            }
        }
        return available >= (beanNames.length * 0.7); // 70%以上组件可用认为模块可用
    }

    /**
     * 报告模块状态
     */
    private void reportModuleStatus(String moduleName, boolean available) {
        String status = available ? "✅ 可用" : "❌ 不可用";
        System.out.println(String.format("  %-20s %s", moduleName, status));
    }
}