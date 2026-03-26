package com.auraboot.framework.base.test;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static com.auraboot.framework.application.tenant.MetaContext.setContext;

/**
 * 爬虫模块测试基类
 * 
 * 提供统一的测试环境配置和租户上下文设置
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Transactional
public abstract class BaseTest {

    protected static final Long TEST_TENANT_ID = -1L;
    protected static final Long TEST_USER_ID = -1000L;
    protected static final String TEST_USER_PID = "test-user";
    protected static final String TEST_USERNAME = "testuser";

    /**
     * 初始化测试环境
     * 设置租户上下文，所有子类测试都会在此租户下执行
     */
    @BeforeAll
    public static void setupTestEnvironment() {
        // 设置测试租户上下文
        MetaContext.setContext(TEST_TENANT_ID,TEST_USER_ID,TEST_USER_PID,TEST_USERNAME);

    }

    /**
     * 测试辅助方法：通过字符串设置租户ID
     * 仅用于测试环境
     */
    public static long setTenantIdFromString(String tenantIdStr) {
        long tenantId;
        try {
            tenantId = Long.parseLong(tenantIdStr);
        } catch (NumberFormatException e) {
            tenantId = tenantIdStr.hashCode();
        }
        if (tenantId < 0) {
            tenantId = -tenantId;
        }
        MetaContext.setContext(TEST_TENANT_ID,TEST_USER_ID,TEST_USER_PID,TEST_USERNAME);

        return tenantId;
    }
}


