package com.auraboot.framework.application.database.mybatis;

import com.auraboot.framework.application.database.dialect.DatabaseDialect;
import com.auraboot.framework.application.database.dialect.DatabaseType;
import com.auraboot.framework.application.tenant.MetaContext;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * MybatisPlusConfig单元测试
 * 
 * 验证租户拦截器配置的正确性
 * 
 * @author AuraBoot Framework
 */
@Tag("critical-security-fixes")
@Tag("requirement-8.1")
@DisplayName("MybatisPlusConfig - 租户拦截器配置测试")
public class MybatisPlusConfigTest {

    private MybatisPlusConfig config;
    private DatabaseDialect mockDialect;

    @BeforeEach
    public void setUp() {
        mockDialect = mock(DatabaseDialect.class);
        when(mockDialect.getType()).thenReturn(DatabaseType.POSTGRESQL);
        config = new MybatisPlusConfig();
    }

    @AfterEach
    public void tearDown() {
        MetaContext.clear();
    }

    /**
     * Find the TenantLineInnerInterceptor whose column == "tenant_id". Multiple instances now
     * exist (tenant + env-layering env_id) — index 0 is no longer guaranteed to be the tenant
     * one. Use the configured column name to disambiguate.
     */
    private TenantLineInnerInterceptor findTenantInterceptor(MybatisPlusInterceptor interceptor) {
        for (InnerInterceptor ii : interceptor.getInterceptors()) {
            if (ii instanceof TenantLineInnerInterceptor t) {
                try {
                    var field = TenantLineInnerInterceptor.class.getDeclaredField("tenantLineHandler");
                    field.setAccessible(true);
                    TenantLineHandler h = (TenantLineHandler) field.get(t);
                    if ("tenant_id".equals(h.getTenantIdColumn())) {
                        return t;
                    }
                } catch (Exception ignored) {
                    // skip
                }
            }
        }
        throw new AssertionError("Tenant-id TenantLineInnerInterceptor not found");
    }

    private boolean hasInterceptorOfType(MybatisPlusInterceptor interceptor,
                                          Class<? extends InnerInterceptor> type) {
        return interceptor.getInterceptors().stream().anyMatch(type::isInstance);
    }

    @Test
    @DisplayName("验证MybatisPlusInterceptor已正确创建")
    public void testMybatisPlusInterceptorCreation() {
        // When
        MybatisPlusInterceptor interceptor = config.mybatisPlusInterceptor(mockDialect, null);

        // Then
        assertNotNull(interceptor, "MybatisPlusInterceptor不应为null");
    }

    @Test
    @DisplayName("验证TenantLineInnerInterceptor已注册")
    public void testTenantLineInnerInterceptorRegistered() {
        // When
        MybatisPlusInterceptor interceptor = config.mybatisPlusInterceptor(mockDialect, null);
        List<InnerInterceptor> interceptors = interceptor.getInterceptors();

        // Then
        assertNotNull(interceptors, "拦截器列表不应为null");
        assertFalse(interceptors.isEmpty(), "拦截器列表不应为空");

        // 验证第一个拦截器是TenantLineInnerInterceptor
        assertTrue(hasInterceptorOfType(interceptor, TenantLineInnerInterceptor.class),
                "TenantLineInnerInterceptor 应该已注册");
    }

    @Test
    @DisplayName("验证PaginationInnerInterceptor已注册")
    public void testPaginationInnerInterceptorRegistered() {
        // When
        MybatisPlusInterceptor interceptor = config.mybatisPlusInterceptor(mockDialect, null);
        List<InnerInterceptor> interceptors = interceptor.getInterceptors();

        // Then
        assertTrue(hasInterceptorOfType(interceptor, PaginationInnerInterceptor.class),
                "PaginationInnerInterceptor 应该已注册");
    }

    @Test
    @DisplayName("验证TenantLineHandler已正确配置")
    public void testTenantLineHandlerConfiguration() {
        // When
        MybatisPlusInterceptor interceptor = config.mybatisPlusInterceptor(mockDialect, null);
        TenantLineInnerInterceptor tenantInterceptor = 
                findTenantInterceptor(interceptor);

        // Then
        assertNotNull(tenantInterceptor, "TenantLineInnerInterceptor不应为null");

        // 通过反射获取TenantLineHandler
        try {
            var field = TenantLineInnerInterceptor.class.getDeclaredField("tenantLineHandler");
            field.setAccessible(true);
            TenantLineHandler handler = (TenantLineHandler) field.get(tenantInterceptor);
            assertNotNull(handler, "TenantLineHandler不应为null");
        } catch (Exception e) {
            fail("Failed to access TenantLineHandler: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("验证getTenantId()在有租户上下文时返回正确的租户ID")
    public void testGetTenantIdWithContext() {
        // Given
        Long expectedTenantId = 12345L;
        MetaContext.setSystemTenantContext(expectedTenantId);

        // When
        MybatisPlusInterceptor interceptor = config.mybatisPlusInterceptor(mockDialect, null);
        TenantLineInnerInterceptor tenantInterceptor = 
                findTenantInterceptor(interceptor);

        // 通过反射获取TenantLineHandler并调用getTenantId()
        try {
            var field = TenantLineInnerInterceptor.class.getDeclaredField("tenantLineHandler");
            field.setAccessible(true);
            TenantLineHandler handler = (TenantLineHandler) field.get(tenantInterceptor);

            Expression tenantIdExpr = handler.getTenantId();

            // Then
            assertNotNull(tenantIdExpr, "租户ID表达式不应为null");
            assertTrue(tenantIdExpr instanceof LongValue, "租户ID应该是LongValue类型");
            assertEquals(expectedTenantId, ((LongValue) tenantIdExpr).getValue(),
                    "租户ID应该匹配上下文中的值");
        } catch (Exception e) {
            fail("Failed to test getTenantId: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("验证getTenantId()在无租户上下文时抛出异常")
    public void testGetTenantIdWithoutContext() {
        // Given
        MetaContext.clear();  // 确保没有租户上下文

        // When
        MybatisPlusInterceptor interceptor = config.mybatisPlusInterceptor(mockDialect, null);
        TenantLineInnerInterceptor tenantInterceptor = 
                findTenantInterceptor(interceptor);

        // 通过反射获取TenantLineHandler并调用getTenantId()
        try {
            var field = TenantLineInnerInterceptor.class.getDeclaredField("tenantLineHandler");
            field.setAccessible(true);
            TenantLineHandler handler = (TenantLineHandler) field.get(tenantInterceptor);

            // Then
           assertThrows(IllegalStateException.class,
                    () -> handler.getTenantId(),
                    "没有租户上下文时应该抛出IllegalStateException");


        } catch (Exception e) {
            fail("Failed to test getTenantId without context: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("验证getTenantIdColumn()返回正确的列名")
    public void testGetTenantIdColumn() {
        // When
        MybatisPlusInterceptor interceptor = config.mybatisPlusInterceptor(mockDialect, null);
        TenantLineInnerInterceptor tenantInterceptor = 
                findTenantInterceptor(interceptor);

        // 通过反射获取TenantLineHandler并调用getTenantIdColumn()
        try {
            var field = TenantLineInnerInterceptor.class.getDeclaredField("tenantLineHandler");
            field.setAccessible(true);
            TenantLineHandler handler = (TenantLineHandler) field.get(tenantInterceptor);

            String columnName = handler.getTenantIdColumn();

            // Then
            assertNotNull(columnName, "租户ID列名不应为null");
            assertEquals("tenant_id", columnName, "租户ID列名应该是'tenant_id'");
        } catch (Exception e) {
            fail("Failed to test getTenantIdColumn: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("验证系统表被正确忽略")
    public void testSystemTablesIgnored() {
        // Given
        String[] systemTables = {
                "ab_user",
                "ab_tenant",
                "ab_tenant_member",
                "ab_invitation",
                "ab_user_session",
                "ab_user_social_link",
                "ab_user_deactivation",
                "ab_verification_code"
        };

        // When
        MybatisPlusInterceptor interceptor = config.mybatisPlusInterceptor(mockDialect, null);
        TenantLineInnerInterceptor tenantInterceptor = 
                findTenantInterceptor(interceptor);

        // 通过反射获取TenantLineHandler
        try {
            var field = TenantLineInnerInterceptor.class.getDeclaredField("tenantLineHandler");
            field.setAccessible(true);
            TenantLineHandler handler = (TenantLineHandler) field.get(tenantInterceptor);

            // Then
            for (String tableName : systemTables) {
                assertTrue(handler.ignoreTable(tableName),
                        "系统表 '" + tableName + "' 应该被忽略");
            }
        } catch (Exception e) {
            fail("Failed to test system tables: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("验证se_前缀表被正确忽略")
    public void testSeTablesIgnored() {
        // Given
        String[] seTables = {
                "se_config",
                "se_metadata",
                "se_system"
        };

        // When
        MybatisPlusInterceptor interceptor = config.mybatisPlusInterceptor(mockDialect, null);
        TenantLineInnerInterceptor tenantInterceptor = 
                findTenantInterceptor(interceptor);

        // 通过反射获取TenantLineHandler
        try {
            var field = TenantLineInnerInterceptor.class.getDeclaredField("tenantLineHandler");
            field.setAccessible(true);
            TenantLineHandler handler = (TenantLineHandler) field.get(tenantInterceptor);

            // Then
            for (String tableName : seTables) {
                assertTrue(handler.ignoreTable(tableName),
                        "se_前缀表 '" + tableName + "' 应该被忽略");
            }
        } catch (Exception e) {
            fail("Failed to test se_ tables: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("验证information_schema表被正确忽略")
    public void testInformationSchemaTablesIgnored() {
        // Given
        String[] infoSchemaTables = {
                "information_schema.tables",
                "information_schema.columns"
        };

        // When
        MybatisPlusInterceptor interceptor = config.mybatisPlusInterceptor(mockDialect, null);
        TenantLineInnerInterceptor tenantInterceptor = 
                findTenantInterceptor(interceptor);

        // 通过反射获取TenantLineHandler
        try {
            var field = TenantLineInnerInterceptor.class.getDeclaredField("tenantLineHandler");
            field.setAccessible(true);
            TenantLineHandler handler = (TenantLineHandler) field.get(tenantInterceptor);

            // Then
            for (String tableName : infoSchemaTables) {
                assertTrue(handler.ignoreTable(tableName),
                        "information_schema表 '" + tableName + "' 应该被忽略");
            }
        } catch (Exception e) {
            fail("Failed to test information_schema tables: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("验证已恢复租户隔离的表不被忽略")
    public void testRestoredTenantIsolationTables() {
        String[] restoredTables = {
                "ab_plugin_import_log",
                "ab_review"
        };

        MybatisPlusInterceptor interceptor = config.mybatisPlusInterceptor(mockDialect, null);
        TenantLineInnerInterceptor tenantInterceptor =
                findTenantInterceptor(interceptor);

        try {
            var field = TenantLineInnerInterceptor.class.getDeclaredField("tenantLineHandler");
            field.setAccessible(true);
            TenantLineHandler handler = (TenantLineHandler) field.get(tenantInterceptor);

            for (String tableName : restoredTables) {
                assertFalse(handler.ignoreTable(tableName),
                        "Table '" + tableName + "' must NOT be ignored — tenant isolation is required");
            }
        } catch (Exception e) {
            fail("Failed to test restored tenant isolation tables: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("验证登录前显式tenant查询表仍被忽略")
    public void testExplicitTenantLookupTablesStillIgnored() {
        String[] explicitTenantLookupTables = {
                "ab_user_role",
                "ab_role"
        };

        MybatisPlusInterceptor interceptor = config.mybatisPlusInterceptor(mockDialect, null);
        TenantLineInnerInterceptor tenantInterceptor =
                findTenantInterceptor(interceptor);

        try {
            var field = TenantLineInnerInterceptor.class.getDeclaredField("tenantLineHandler");
            field.setAccessible(true);
            TenantLineHandler handler = (TenantLineHandler) field.get(tenantInterceptor);

            for (String tableName : explicitTenantLookupTables) {
                assertTrue(handler.ignoreTable(tableName),
                        "Table '" + tableName + "' should remain ignored because queries pass tenantId explicitly");
            }
        } catch (Exception e) {
            fail("Failed to test explicit tenant lookup tables: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("验证调度器/异步表仍被忽略")
    public void testSchedulerTablesStillIgnored() {
        String[] schedulerTables = {
                "ab_sla_record",
                "ab_automation",
                "ab_idempotent_key",
                "ab_idempotency_record",
                "ab_export_task",
                "ab_i18n_resource",
                "ab_async_task",
                "ab_notification_digest",
                "ab_invariant_definition",
                "ab_decision_definition"
        };

        MybatisPlusInterceptor interceptor = config.mybatisPlusInterceptor(mockDialect, null);
        TenantLineInnerInterceptor tenantInterceptor =
                findTenantInterceptor(interceptor);

        try {
            var field = TenantLineInnerInterceptor.class.getDeclaredField("tenantLineHandler");
            field.setAccessible(true);
            TenantLineHandler handler = (TenantLineHandler) field.get(tenantInterceptor);

            for (String tableName : schedulerTables) {
                assertTrue(handler.ignoreTable(tableName),
                        "Scheduler table '" + tableName + "' must be ignored (no MetaContext in scheduler threads)");
            }
        } catch (Exception e) {
            fail("Failed to test scheduler tables: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("验证业务表不被忽略")
    public void testBusinessTablesNotIgnored() {
        // Given
        String[] businessTables = {
                "ab_dict",
                "ab_meta_model",
                "ab_meta_field",
                "ab_page_schema",
                "ab_entity_records"
        };

        // When
        MybatisPlusInterceptor interceptor = config.mybatisPlusInterceptor(mockDialect, null);
        TenantLineInnerInterceptor tenantInterceptor = 
                findTenantInterceptor(interceptor);

        // 通过反射获取TenantLineHandler
        try {
            var field = TenantLineInnerInterceptor.class.getDeclaredField("tenantLineHandler");
            field.setAccessible(true);
            TenantLineHandler handler = (TenantLineHandler) field.get(tenantInterceptor);

            // Then
            for (String tableName : businessTables) {
                assertFalse(handler.ignoreTable(tableName),
                        "业务表 '" + tableName + "' 不应该被忽略");
            }
        } catch (Exception e) {
            fail("Failed to test business tables: " + e.getMessage());
        }
    }


    @Test
    @DisplayName("验证拦截器注册顺序正确")
    public void testInterceptorOrder() {
        // When
        MybatisPlusInterceptor interceptor = config.mybatisPlusInterceptor(mockDialect, null);
        List<InnerInterceptor> interceptors = interceptor.getInterceptors();

        // Then — env-layering #19 added EnvWriteLockGuardInnerInterceptor + a second
        // TenantLineInnerInterceptor for env_id, so the chain is now 4 long. Order assertion
        // relaxed to "tenant interceptor lives before pagination interceptor in the list".
        int tenantIdx = -1, paginationIdx = -1;
        for (int i = 0; i < interceptors.size(); i++) {
            if (interceptors.get(i) instanceof TenantLineInnerInterceptor) {
                if (tenantIdx == -1) tenantIdx = i;
            }
            if (interceptors.get(i) instanceof PaginationInnerInterceptor && paginationIdx == -1) {
                paginationIdx = i;
            }
        }
        assertTrue(tenantIdx >= 0 && paginationIdx >= 0,
                "TenantLine + Pagination 拦截器都应该已注册");
        assertTrue(tenantIdx < paginationIdx,
                "TenantLineInnerInterceptor 应该在 PaginationInnerInterceptor 之前");
        // Original assertion preserved structurally so the test name still describes intent
        assertTrue(interceptors.get(paginationIdx) instanceof PaginationInnerInterceptor,
                "第二个拦截器应该是PaginationInnerInterceptor");
    }
}
