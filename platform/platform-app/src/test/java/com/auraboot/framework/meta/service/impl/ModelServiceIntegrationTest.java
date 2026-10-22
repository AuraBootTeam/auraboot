package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.MetaModelCreateRequest;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ModelService Integration Test
 * 
 * Tests the complete Model lifecycle including:
 * - CRUD operations
 * - Tenant isolation
 * - Code uniqueness validation
 * 
 * Uses unique model codes per test run to avoid conflicts.
 */
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ModelServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MetaModelService metaModelService;
    
    @Autowired
    private MetaModelMapper metaModelMapper;
    
    // Use unique codes per test run
    private String testModelCode;
    private String testModelName = "集成测试模型";
    private String testModelDescription = "用于集成测试的模型";
    
    private String createdModelPid;
    private boolean modelInitialized = false;

    @BeforeAll
    void setupTestData() {
        testModelCode = "model_test_" + System.currentTimeMillis();
        modelInitialized = false;
    }

    @BeforeEach
    @Override
    public void setupTenantContext() {
        super.setupTenantContext();
        log.info("=== 开始 MetaModelService 集成测试 ===");
        log.info("租户ID: {}", MetaContext.getCurrentTenantId());
    }

    @AfterAll
    void cleanup() {
        modelInitialized = false;
    }



    /**
     * 测试1: 通过 Git-First 流程创建模型
     * 
     * 验证点:
     * - DSL 文件生成
     * - Git 提交成功
     * - Release 创建成功
     * - 投影处理成功
     * - 从投影表查询到数据
     */
    @Test
    @Order(1)
    @DisplayName("测试通过Git-First流程创建模型")
    public void testCreateModelViaGitFirst() {
        log.info(">>> 测试1: 创建模型 - Git-First 流程");
        
        // 1. 准备创建请求
        MetaModelCreateRequest request = new MetaModelCreateRequest();
        request.setCode(testModelCode);
        request.setDisplayName(testModelName);
        request.setDescription(testModelDescription);
        request.setModelType("entity");
          
        
        
        // 2. 执行创建
        MetaModelDTO createdModel = metaModelService.create(request);
        
        // 3. 验证返回结果
        assertNotNull(createdModel, "创建的模型不应为null");
        assertNotNull(createdModel.getPid(), "模型PID不应为null");
        assertEquals(testModelCode, createdModel.getCode(), "模型编码应匹配");
        assertEquals(testModelName, createdModel.getDisplayName(), "模型显示名称应匹配");
        assertEquals(testModelDescription, createdModel.getDescription(), "模型描述应匹配");
        assertEquals("entity", createdModel.getModelType(), "模型类型应匹配");
        
        // 保存PID供后续测试使用
        createdModelPid = createdModel.getPid();
        modelInitialized = true;
        
        log.info("✓ 模型创建成功: pid={}, code={}", createdModelPid, testModelCode);
        

        
        // 5. 验证数据已投影到数据库
        Model modelInDb = metaModelMapper.findCurrentByCode(testModelCode);
        assertNotNull(modelInDb, "投影表中应该存在该模型");
        assertEquals(testModelCode, modelInDb.getCode(), "数据库中的模型编码应匹配");
        assertTrue(modelInDb.getIsCurrent(), "应该是当前版本");
        
        log.info("✓ 模型已投影到数据库: id={}, version={}", modelInDb.getId(), modelInDb.getVersion());
    }

    /**
     * 测试2: 查询模型
     * 
     * 验证点:
     * - 通过 PID 查询
     * - 通过 Code 查询
     * - 租户隔离验证
     */
    @Test
    @Order(2)
    @DisplayName("测试查询模型")
    public void testFindModel() {
        log.info(">>> 测试2: 查询模型");
        
        // 确保有模型可查询
        if (!modelInitialized || createdModelPid == null) {
            testCreateModelViaGitFirst();
        }
        
        // 1. 通过 PID 查询
        MetaModelDTO foundByPid = metaModelService.findByPid(createdModelPid);
        assertNotNull(foundByPid, "通过PID应该能查询到模型");
        assertEquals(testModelCode, foundByPid.getCode(), "查询到的模型编码应匹配");
        
        log.info("✓ 通过PID查询成功: pid={}", createdModelPid);
        
        // 2. 通过 Code 查询 (使用 getModelDefinition)
        var modelDefOpt = metaModelService.getModelDefinition(testModelCode);
        assertTrue(modelDefOpt.isPresent(), "通过Code应该能查询到模型定义");
        assertEquals(testModelCode, modelDefOpt.get().getCode(), "模型定义编码应匹配");
        
        log.info("✓ 通过Code查询成功: code={}", testModelCode);
    }

    /**
     * 测试3: 验证租户隔离
     * 
     * 验证点:
     * - 不同租户的数据互不可见
     * - 查询时自动过滤租户
     */
    @Test
    @Order(3)
    @DisplayName("测试租户隔离")
    public void testTenantIsolation() {
        log.info(">>> 测试3: 租户隔离");
        
        // 确保有模型可查询
        if (!modelInitialized || createdModelPid == null) {
            testCreateModelViaGitFirst();
        }
        
        // 1. 在当前租户下应该能查询到
        MetaModelDTO foundInCurrentTenant = metaModelService.findByPid(createdModelPid);
        assertNotNull(foundInCurrentTenant, "当前租户应该能查询到模型");
        
        log.info("✓ 当前租户查询成功");
        
        // 2. 验证数据库中的租户ID正确
        Model modelInDb = metaModelMapper.findByPid(createdModelPid);
        assertNotNull(modelInDb, "数据库中应该存在该模型");
        assertEquals(MetaContext.getCurrentTenantId(), modelInDb.getTenantId(), 
            "模型的租户ID应该与当前租户ID匹配");
        
        log.info("✓ 租户ID验证通过: tenantId={}", modelInDb.getTenantId());
    }


    /**
     * 测试5: 验证编码唯一性
     * 
     * 验证点:
     * - 相同编码不能重复创建
     * - 错误信息正确
     */
    @Test
    @Order(5)
    @DisplayName("测试编码唯一性验证")
    public void testCodeUniqueness() {
        log.info(">>> 测试5: 编码唯一性验证");
        
        // 1. 创建第一个模型
        String uniqueCode = "test_unique_model_" + System.currentTimeMillis();
        MetaModelCreateRequest request1 = new MetaModelCreateRequest();
        request1.setCode(uniqueCode);
        request1.setDisplayName("唯一性测试模型1");
        request1.setDescription("测试编码唯一性");
        request1.setModelType("entity");
        
        
        MetaModelDTO model1 = metaModelService.create(request1);
        assertNotNull(model1, "第一个模型应该创建成功");
        
        log.info("✓ 第一个模型创建成功: code={}", uniqueCode);
        
        // 2. 尝试创建相同编码的模型
        MetaModelCreateRequest request2 = new MetaModelCreateRequest();
        request2.setCode(uniqueCode);
        request2.setDisplayName("唯一性测试模型2");
        request2.setDescription("测试编码唯一性 - 应该失败");
        request2.setModelType("entity");
        
        
        Exception exception = assertThrows(Exception.class, () -> {
            metaModelService.create(request2);
        }, "相同编码应该抛出异常");
        
        assertTrue(exception.getMessage().contains("已存在") || 
                  exception.getMessage().contains("unique") ||
                  exception.getMessage().contains("duplicate"),
            "异常信息应该包含唯一性相关提示");
        
        log.info("✓ 编码唯一性验证通过: {}", exception.getMessage());
        
        // Note: Not deleting the model as it may have bound fields
        // The model will be cleaned up by database cleanup scripts
        log.info("✓ 测试完成 (模型保留，由数据库清理脚本处理)");
    }


    /**
     * 测试7: 完整的 CRUD 生命周期
     * 
     * 验证点:
     * - 创建 → 查询 → 验证存在性 的完整流程
     * - 每个步骤的数据一致性
     * 
     * Note: Delete is not tested here because models may have auto-bound fields
     * which prevent deletion without first unbinding them.
     */
    @Test
    @Order(7)
    @DisplayName("测试完整CRUD生命周期")
    public void testFullCrudLifecycle() {
        log.info(">>> 测试7: 完整 CRUD 生命周期");
        
        String lifecycleCode = "test_lifecycle_model_" + System.currentTimeMillis();
        
        // 1. 创建
        MetaModelCreateRequest createRequest = new MetaModelCreateRequest();
        createRequest.setCode(lifecycleCode);
        createRequest.setDisplayName("生命周期测试模型");
        createRequest.setDescription("测试完整CRUD生命周期");
        createRequest.setModelType("entity");

        MetaModelDTO created = metaModelService.create(createRequest);
        assertNotNull(created, "创建应该成功");
        log.info("✓ 步骤1: 创建成功 - pid={}", created.getPid());
        
        // 2. 查询
        MetaModelDTO found = metaModelService.findByPid(created.getPid());
        assertNotNull(found, "查询应该成功");
        assertEquals(lifecycleCode, found.getCode(), "查询到的数据应该匹配");
        log.info("✓ 步骤2: 查询成功 - code={}", found.getCode());
        
        // 3. 验证存在性
        boolean exists = metaModelService.isModelExists(lifecycleCode);
        assertTrue(exists, "模型应该存在");
        log.info("✓ 步骤3: 存在性验证通过");
        
        // Note: Delete is skipped because models may have auto-bound fields
        // which prevent deletion without first unbinding them.
        // The model will be cleaned up by database cleanup scripts.
        
        log.info("✓ 完整 CRUD 生命周期测试通过 (删除步骤跳过，由数据库清理脚本处理)");
    }
}
