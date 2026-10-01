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

    private TenantLineInnerInterceptor tenantInterceptor() {
        DatabaseDialect dialect = mock(DatabaseDialect.class);
        when(dialect.getType()).thenReturn(DatabaseType.POSTGRESQL);
        MybatisPlusInterceptor interceptor = new MybatisPlusConfig()
                .mybatisPlusInterceptor(dialect, mock(org.springframework.context.ApplicationContext.class),
                        mock(org.springframework.core.env.Environment.class));
        return interceptor.getInterceptors().stream()
                .filter(TenantLineInnerInterceptor.class::isInstance)
                .map(TenantLineInnerInterceptor.class::cast)
                .findFirst()
                .orElseThrow();
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

    @Nested
    class RegistryLedger {

        @Test
        void registrySetsAreDisjointAndClean() {
            for (String name : MybatisPlusConfig.VERIFIED_GLOBAL_TABLES) {
                assertFalse(MybatisPlusConfig.MIGRATION_PENDING_TABLES.contains(name),
                        name + " cannot be both verified-global and migration-pending");
                assertTrue(name.startsWith("ab_"));
            }
            for (String name : MybatisPlusConfig.MIGRATION_PENDING_TABLES) {
                assertTrue(name.startsWith("ab_"));
            }
        }

        @Test
        void pendingLedgerCountsDownTowardZero() {
            // The ledger IS the campaign dashboard: every wave that removes an
            // exemption updates its expected count here. 33 = post-W2a state.
            assertEquals(19, MybatisPlusConfig.MIGRATION_PENDING_TABLES.size(),
                    "W3d removed the scheduled-task pair (engine lifecycle + executor plane wrapped); update this ledger per wave");
            assertEquals(15, MybatisPlusConfig.VERIFIED_GLOBAL_TABLES.size());
        }

        @Test
        void abUserSessionIsNoLongerExempt() {
            var handler = tenantInterceptor().getTenantLineHandler();
            assertFalse(handler.ignoreTable("ab_user_session"),
                    "W2a removed this exemption; the tenant filter must apply");
        }

        @Test
        void abExchangeRateIsNoLongerExempt() {
            var handler = tenantInterceptor().getTenantLineHandler();
            assertFalse(handler.ignoreTable("ab_exchange_rate"),
                    "W4a removed this exemption; explicit-param queries are same-tenant as context");
        }

        @Test
        void w3aSeedersAndCleanupsAreNoLongerExempt() {
            var handler = tenantInterceptor().getTenantLineHandler();
            for (String table : new String[]{"ab_i18n_resource", "ab_cloud_config",
                    "ab_idempotency_record", "ab_idempotent_key"}) {
                assertFalse(handler.ignoreTable(table),
                        "W3a removed this exemption; seams are wrapped in PlatformSeedService / cleanup tasks");
            }
        }

        @Test
        void w3dScheduledTaskPairIsNoLongerExempt() {
            var handler = tenantInterceptor().getTenantLineHandler();
            for (String table : new String[]{"ab_scheduled_task", "ab_scheduled_task_log"}) {
                assertFalse(handler.ignoreTable(table),
                        "W3d removed these exemptions; engine + executor planes are scoped, handlers stay fail-closed");
            }
        }

        @Test
        void w3cAlarmDefinitionTablesAreNoLongerExempt() {
            var handler = tenantInterceptor().getTenantLineHandler();
            for (String table : new String[]{"ab_invariant_definition", "ab_decision_definition"}) {
                assertFalse(handler.ignoreTable(table),
                        "W3c removed these exemptions; worker entry seams wrapped in W3b");
            }
        }

        @Test
        void w3bAsyncAndBehaviorTablesAreNoLongerExempt() {
            var handler = tenantInterceptor().getTenantLineHandler();
            for (String table : new String[]{"ab_behavior_event", "ab_behavior_quarantine",
                    "ab_behavior_outcome_outbox", "ab_export_task", "ab_async_task"}) {
                assertFalse(handler.ignoreTable(table),
                        "W3b removed these exemptions; @Async executors propagate tenant, MQ/scheduled seams wrapped");
            }
        }
    }

    @Nested
    class PrefixBypassGuard {

        private final MybatisPlusConfig config = new MybatisPlusConfig();

        private org.springframework.core.env.Environment env(String... profiles) {
            var environment = mock(org.springframework.core.env.Environment.class);
            when(environment.getActiveProfiles()).thenReturn(profiles);
            return environment;
        }

        @Test
        void emptyPrefixNeverFails() {
            org.springframework.test.util.ReflectionTestUtils.setField(config, "tenantBypassTablePrefixes", "");
            assertDoesNotThrow(() -> config.guardTenantBypassPrefixes(env("production")));
        }

        @Test
        void prefixInNonDevProfileFailsStartup() {
            org.springframework.test.util.ReflectionTestUtils.setField(config, "tenantBypassTablePrefixes", "ext_,partner_");
            var error = assertThrows(IllegalStateException.class,
                    () -> config.guardTenantBypassPrefixes(env("production")));
            assertTrue(error.getMessage().contains("tenant-bypass-table-prefixes"));
        }

        @Test
        void prefixInDevProfileWarnsOnly() {
            org.springframework.test.util.ReflectionTestUtils.setField(config, "tenantBypassTablePrefixes", "ext_");
            assertDoesNotThrow(() -> config.guardTenantBypassPrefixes(env("dev")));
        }
    }
}
