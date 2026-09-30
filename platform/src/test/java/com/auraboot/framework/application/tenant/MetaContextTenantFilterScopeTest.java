package com.auraboot.framework.application.tenant;

import com.auraboot.framework.application.database.dialect.DatabaseDialect;
import com.auraboot.framework.application.database.dialect.DatabaseType;
import com.auraboot.framework.application.database.mybatis.MybatisPlusConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * W1 of the tenant-exemption cleanup: the explicit code-level tenant-filter
 * scope that replaces blanket table exemptions. Business semantics stay
 * fail-closed: without the scope, a context-less query on a non-exempt table
 * must still be tenant-filtered.
 */
class MetaContextTenantFilterScopeTest {

    @AfterEach
    void clear() {
        MetaContext.clear();
    }

    @Nested
    class ScopeSemantics {

        @Test
        void defaultIsNotBypassed() {
            MetaContext.clear();
            assertFalse(MetaContext.isTenantFilterBypassed());
        }

        @Test
        void scopeSetsFlagInsideAndRestoresAfter() {
            AtomicBoolean inside = new AtomicBoolean(false);
            String result = MetaContext.runWithoutTenantFilter(() -> {
                inside.set(MetaContext.isTenantFilterBypassed());
                return "value";
            });
            assertEquals("value", result);
            assertTrue(inside.get(), "flag must be active inside the scope");
            assertFalse(MetaContext.isTenantFilterBypassed(), "flag must be restored after the scope");
        }

        @Test
        void runnableOverloadRuns() {
            AtomicBoolean inside = new AtomicBoolean(false);
            MetaContext.runWithoutTenantFilter((Runnable) () -> inside.set(MetaContext.isTenantFilterBypassed()));
            assertTrue(inside.get());
            assertFalse(MetaContext.isTenantFilterBypassed());
        }

        @Test
        void scopeIsNestableAndRestoresOuterState() {
            MetaContext.runWithoutTenantFilter(() -> {
                assertTrue(MetaContext.isTenantFilterBypassed());
                MetaContext.runWithoutTenantFilter(() -> { });
                assertTrue(MetaContext.isTenantFilterBypassed(),
                        "inner scope exit must restore the outer scope's flag, not clear it");
            });
            assertFalse(MetaContext.isTenantFilterBypassed());
        }

        @Test
        void flagIsRestoredEvenWhenActionThrows() {
            assertThrows(IllegalStateException.class, () ->
                    MetaContext.runWithoutTenantFilter(() -> {
                        throw new IllegalStateException("boom");
                    }));
            assertFalse(MetaContext.isTenantFilterBypassed(), "exception must not leak the bypass flag");
        }

        @Test
        void clearRemovesStaleFlag() {
            MetaContext.runWithoutTenantFilter(() -> { });
            // Direct stale-flag simulation is impossible through the public API by
            // design; clear() must still remove the ThreadLocal defensively.
            MetaContext.clear();
            assertFalse(MetaContext.isTenantFilterBypassed());
        }
    }

    @Nested
    class TenantLineInterceptorWiring {

        private TenantLineInnerInterceptor tenantInterceptor() {
            DatabaseDialect dialect = mock(DatabaseDialect.class);
            when(dialect.getType()).thenReturn(DatabaseType.POSTGRESQL);
            MybatisPlusInterceptor interceptor = new MybatisPlusConfig()
                    .mybatisPlusInterceptor(dialect, mock(org.springframework.context.ApplicationContext.class));
            return interceptor.getInterceptors().stream()
                    .filter(TenantLineInnerInterceptor.class::isInstance)
                    .map(TenantLineInnerInterceptor.class::cast)
                    .findFirst()
                    .orElseThrow();
        }

        @Test
        void businessTableIsFilteredWithoutScope() {
            var handler = tenantInterceptor().getTenantLineHandler();
            // ab_agent_definition is a tenant_id-bearing business table that is
            // NOT on the (transitional W1) exemption list — the flag is the only
            // determinant. W2's acceptance test asserts the same for ab_role
            // after its exemption entry is removed.
            assertFalse(handler.ignoreTable("ab_agent_definition"));
        }

        @Test
        void scopeSuppressesFilterForBusinessTable() {
            var handler = tenantInterceptor().getTenantLineHandler();
            MetaContext.runWithoutTenantFilter(() ->
                    assertTrue(handler.ignoreTable("ab_agent_definition"),
                            "explicit scope must suppress the tenant-line filter"));
            assertFalse(handler.ignoreTable("ab_agent_definition"), "scope exit restores filtering");
        }

        @Test
        void w1TransitionalListEntryStillExemptsOutsideScope() {
            // W1 keeps the existing list untouched (zero behavior change). This
            // assertion documents the transitional state; it is replaced by
            // W2's "ab_role is filtered" test when the entry is removed.
            var handler = tenantInterceptor().getTenantLineHandler();
            assertTrue(handler.ignoreTable("ab_role"));
        }

        @Test
        void globalTableRemainsExemptInsideAndOutsideScope() {
            var handler = tenantInterceptor().getTenantLineHandler();
            assertTrue(handler.ignoreTable("ab_user"));
            MetaContext.runWithoutTenantFilter(() ->
                    assertTrue(handler.ignoreTable("ab_user")));
        }
    }
}
