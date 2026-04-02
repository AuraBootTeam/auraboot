package com.auraboot.framework.integration;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.entity.UserRole;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.test.util.TestResourceTracker;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.time.Instant;
import java.util.Arrays;

/**
 * 基础集成测试
 * 验证测试环境和基础设施是否正常工作
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Transactional
@Rollback(true)  // 强制回滚所有数据库操作,确保测试数据清理
public class BaseIntegrationTest {

    private static final Object TEST_DATA_LOCK = new Object();
    private static volatile boolean testDataInitialized = false;

    @Autowired
    protected ApplicationContext applicationContext;

    // Mock external dependencies per project convention: mail/SMS always mocked
    @MockitoBean
    protected JavaMailSender mailSender;

    // 测试数据存储
    protected static User testUser;
    protected static Tenant testTenant;
    protected static TenantMember testTenantMember;
    protected static Role testRole;
    protected static UserRole testUserRole;

    @Autowired
    private UserService userService;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private TenantMemberService tenantMemberService;

    @Autowired
    private RoleService roleService;

    @Autowired
    private UserRoleService userRoleService;

    @Autowired
    private PlatformTransactionManager transactionManager;



    // 资源追踪器 - 每个测试独立
    private TestResourceTracker resourceTracker;


    @BeforeAll
    public static void initBeforeAllTest() {
        // 静态初始化，在所有测试开始前执行一次
    }

    @BeforeEach
    public void setupTenantContext() {
        // 1. 初始化资源追踪器
        resourceTracker = new TestResourceTracker();

        // 2. 确保测试数据存在
        ensureTestDataExists();
    }

    @AfterEach
    public void clearTenantContext() {
        MetaContext.clear();
    }

    /**
     * 确保测试数据存在，如果不存在则创建
     */
    private void ensureTestDataExists() {
        if (testDataInitialized
                && testUser != null
                && testTenant != null
                && testTenantMember != null
                && testRole != null
                && testUserRole != null) {
            MetaContext.setContext(
                    testTenant.getId(),
                    testUser.getId(),
                    testUser.getPid(),
                    testUser.getUserName()
            );
            MetaContext.setMemberId(testTenantMember.getId());
            return;
        }

        synchronized (TEST_DATA_LOCK) {
            if (testDataInitialized
                    && testUser != null
                    && testTenant != null
                    && testTenantMember != null
                    && testRole != null
                    && testUserRole != null) {
                MetaContext.setContext(
                        testTenant.getId(),
                        testUser.getId(),
                        testUser.getPid(),
                        testUser.getUserName()
                );
                MetaContext.setMemberId(testTenantMember.getId());
                return;
            }

            try {
                TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
                transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
                transactionTemplate.executeWithoutResult(status -> {
                    testUser = createTestUser();
                    testTenant = createTestTenant();
                    testTenantMember = createTestTenantMember();

                    MetaContext.setContext(
                            testTenant.getId(),
                            testUser.getId(),
                            testUser.getPid(),
                            testUser.getUserName()
                    );
                    MetaContext.setMemberId(testTenantMember.getId());

                    // 创建测试角色并为用户分配角色，仅初始化一次。
                    testRole = createTestRole();
                    testUserRole = createTestUserRole();
                });
                testDataInitialized = true;
            } catch (Exception e) {
                throw new RuntimeException("Failed to setup test data", e);
            }
        }
    }
    
    /**
     * 创建测试用户
     */
    private User createTestUser() {
        String testEmail = "integration-test@auraboot.com";
        
        // 先尝试查找现有用户
        User existingUser = userService.findByEmail(testEmail);
        if (existingUser != null) {
            return existingUser;
        }
        
        try {
            // 使用signUp方法创建用户，这是UserService的标准方法
            User createdUser = userService.signUp(testEmail, "test-password-123");
            if (createdUser != null && createdUser.getId() != null) {
                return createdUser;
            } else {
                throw new RuntimeException("signUp returned null or user without ID");
            }
        } catch (Exception e) {
            // 如果signUp失败（可能因为用户已存在），尝试直接查找
            User foundUser = userService.findByEmail(testEmail);
            if (foundUser != null) {
                return foundUser;
            }
            throw new RuntimeException("Failed to create test user: " + e.getMessage(), e);
        }
    }
    
    /**
     * 创建测试租户
     */
    private Tenant createTestTenant() {
        String testTenantName = "integration-test-tenant";
        
        // 先尝试查找现有租户
        Tenant existingTenant = tenantService.findByName(testTenantName);
        if (existingTenant != null) {
            return existingTenant;
        }
        
        // 创建新租户
        Tenant tenant = new Tenant();
        tenant.setPid(UniqueIdGenerator.generate());
        tenant.setName(testTenantName);
        tenant.setDisplayName("Integration Test Tenant");
        tenant.setStatus("active");
        tenant.setContactEmail("admin@integration-test.com");
        tenant.setDescription("Integration test tenant for automated testing");
        tenant.setDeletedFlag(false);
        tenant.setCreatedAt(Instant.now());
        tenant.setUpdatedAt(Instant.now());
        
        return tenantService.createTenant(tenant);
    }
    
    /**
     * 创建测试租户成员关系
     */
    private TenantMember createTestTenantMember() {
        // 先检查是否已存在关系
        TenantMember existingMember = tenantMemberService.findByTenantIdAndUserId(
            testTenant.getId(), testUser.getId());
        if (existingMember != null) {
            return existingMember;
        }
        
        // 创建新的租户成员关系
        return tenantMemberService.addMember(testUser.getId(), testTenant.getId(), "active");
    }

    /**
     * 创建测试角色
     */
    private Role createTestRole() {
        // 直接创建新角色，不查找现有角色（避免租户上下文问题）
        Role role = new Role();
        role.setPid(UniqueIdGenerator.generate());
        role.setName("test_user");
        role.setCode("test_user");
        role.setDescription("集成测试用户角色");
        role.setType("custom");
        role.setScopeType("tenant");
        role.setStatus("active");
        role.setTenantId(testTenant.getId());
        role.setIsDefault(false);
        role.setIsSystem(false);
        role.setDeletedFlag(false);
        role.setPriority(100);
        role.setCreatedAt(Instant.now());
        role.setUpdatedAt(Instant.now());

        return roleService.createRole(role);
    }

    /**
     * 为用户分配角色
     */
    private UserRole createTestUserRole() {
        // 先检查是否已存在关系
        UserRole existingUserRole = userRoleService.findByMemberIdAndRoleIdAndTenantId(
            testTenantMember.getId(), testRole.getId(), testTenant.getId());
        if (existingUserRole != null) {
            return existingUserRole;
        }

        // 为用户分配角色
        boolean success = userRoleService.assignRolesToMember(
            testTenantMember.getId(),
            Arrays.asList(testRole.getId()), 
            testTenant.getId(), 
            null
        );

        if (success) {
            return userRoleService.findByMemberIdAndRoleIdAndTenantId(
                testTenantMember.getId(), testRole.getId(), testTenant.getId());
        }

        return null;
    }

    
    // ========== 资源注册方法 ==========

    /**
     * 注册模型,测试结束后会清理物理表
     *
     * @param modelCode 模型编码
     */
    protected void trackModel(String modelCode) {
        resourceTracker.addModel(modelCode);
    }

    /**
     * 注册Release,测试结束后会清理 (可选,@Rollback会自动处理)
     *
     * @param releaseId Release ID
     */
    protected void trackRelease(Long releaseId) {
        resourceTracker.addRelease(releaseId);
    }

    // ========== 测试数据获取方法 ==========

    /**
     * 获取测试用户
     */
    protected User getTestUser() {
        return testUser;
    }
    
    /**
     * 获取测试租户
     */
    protected Tenant getTestTenant() {
        return testTenant;
    }
    
    /**
     * 获取测试租户成员
     */
    protected TenantMember getTestTenantMember() {
        return testTenantMember;
    }

    /**
     * 获取测试角色
     */
    protected Role getTestRole() {
        return testRole;
    }

    /**
     * 获取测试用户角色关系
     */
    protected UserRole getTestUserRole() {
        return testUserRole;
    }
}
