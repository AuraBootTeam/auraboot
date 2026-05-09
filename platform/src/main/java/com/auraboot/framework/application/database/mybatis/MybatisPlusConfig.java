package com.auraboot.framework.application.database.mybatis;

import com.auraboot.framework.application.database.dialect.DatabaseDialect;
import com.auraboot.framework.application.database.dialect.DatabaseType;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.environment.annotation.EnvScoped;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.util.HashSet;
import java.util.Set;

@Slf4j
@Configuration
public class MybatisPlusConfig {

    /** Static cache populated on first access. Drop-in replacement for the prior hardcoded Set. */
    private static volatile Set<String> envScopedTables;

    /**
     * Whitelist of tables backing {@code @EnvScoped} entities, discovered via classpath scan
     * (env-layering #18). Adding a new env-scoped resource is now one-step: annotate the
     * entity. The MyBatis-Plus interceptor reads this set on every query.
     *
     * <p>Package-visible so {@code EnvWriteLockGuardInnerInterceptor} can reuse the same
     * lookup (#19 — UPDATE/DELETE lock guard).
     */
    static Set<String> envScopedTables() {
        Set<String> cached = envScopedTables;
        if (cached != null) return cached;
        synchronized (MybatisPlusConfig.class) {
            if (envScopedTables != null) return envScopedTables;
            Set<String> tables = new HashSet<>();
            ClassPathScanningCandidateComponentProvider scanner =
                    new ClassPathScanningCandidateComponentProvider(false);
            scanner.addIncludeFilter(new AnnotationTypeFilter(EnvScoped.class));
            for (var bd : scanner.findCandidateComponents("com.auraboot")) {
                String className = bd.getBeanClassName();
                if (className == null) continue;
                try {
                    Class<?> clazz = Class.forName(className);
                    TableName tn = clazz.getAnnotation(TableName.class);
                    if (tn != null && !tn.value().isBlank()) {
                        tables.add(tn.value());
                    }
                } catch (ClassNotFoundException e) {
                    log.warn("Failed to load @EnvScoped candidate {}: {}", className, e.getMessage());
                }
            }
            log.info("Resolved env-scoped tables via classpath scan: {}", tables);
            envScopedTables = Set.copyOf(tables);
            return envScopedTables;
        }
    }

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(DatabaseDialect databaseDialect,
                                                          ApplicationContext applicationContext) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // env-layering #19 — UPDATE/DELETE write-side lock guard. Registered FIRST so it sees
        // the unmodified SQL (no tenant/env WHERE clauses appended yet). beforeUpdate fires
        // before tenant-line / env-line interceptors mutate boundSql.
        interceptor.addInnerInterceptor(new EnvWriteLockGuardInnerInterceptor(applicationContext));

        TenantLineInnerInterceptor tenantInterceptor = new TenantLineInnerInterceptor();
        tenantInterceptor.setTenantLineHandler(new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                Long tenantId = MetaContext.getCurrentTenantId();
                // When user has no tenant context (e.g., multi-tenant login before space selection),
                // return -1 so tenant-filtered queries return empty results instead of throwing.
                // Tables like ab_user are already in ignoreTable and won't be affected.
                if (tenantId == null) {
                    return new LongValue(-1);
                }
                return new LongValue(tenantId);
            }

            @Override
            public String getTenantIdColumn() {
                return "tenant_id";
            }

            @Override
            public boolean ignoreTable(String tableName) {
                // ── Global tables (no tenant_id column) ──
                return "ab_user".equals(tableName)
                    || "ab_tenant".equals(tableName)
                    || "ab_tenant_member".equals(tableName)           // Cross-tenant: "which tenants does user belong to"
                    || "ab_invitation".equals(tableName)              // Pre-join: invitation verified before tenant context
                    || "ab_user_session".equals(tableName)            // No tenant_id
                    || "ab_user_social_link".equals(tableName)        // No tenant_id, global per user
                    || "ab_user_deactivation".equals(tableName)       // No tenant_id
                    || "ab_verification_code".equals(tableName)       // No tenant_id, pre-auth OTP
                    || "ab_system_config".equals(tableName)           // G1: no tenant_id
                    || "ab_bootstrap".equals(tableName)               // G1: no tenant_id
                    || "ab_platform_account".equals(tableName)      // G1: no tenant_id
                    || "ab_platform_license".equals(tableName)      // G1: no tenant_id
                    || "ab_api_connector_endpoint".equals(tableName)  // No tenant_id
                    || "ab_jdbc_connector_endpoint".equals(tableName)  // No tenant_id (parented by connector_pid)
                    || "ab_mkt_publisher_payout".equals(tableName)    // No tenant_id, publisher-scoped (not tenant-scoped)

                    // ── Currency (has tenant_id, but all queries pass it explicitly as @Param) ──
                    || "ab_exchange_rate".equals(tableName)           // ExchangeRateMapper passes tenantId explicitly

                    // ── Consolidation (explicit tenantId in @Select queries) ──
                    || "ab_legal_entity".equals(tableName)
                    || "ab_intercompany_txn".equals(tableName)

                    // ── RBAC (has tenant_id, but queried during login before MetaContext is set) ──
                    || "ab_user_role".equals(tableName)              // Login: countUserRolesInTenant passes tenantId explicitly
                    || "ab_role".equals(tableName)                   // Login: role lookup by tenantId explicitly

                    // ── Admin/entitlement (has tenant_id, but admin queries use explicit tenantId) ──
                    || "ab_tenant_entitlement".equals(tableName)
                    || "ab_license_audit_log".equals(tableName)
                    || "ab_payment_order".equals(tableName)
                    || "ab_payment_transaction".equals(tableName)
                    || "ab_marketplace_solution_install".equals(tableName)
                    || "ab_tenant_login_channel".equals(tableName)    // Queried by explicit tenantId before auth

                    // ── Permission audit (has tenant_id, but @Async writes run without MetaContext) ──
                    || "ab_permission_audit_log".equals(tableName)

                    // ── Scheduler/async context (has tenant_id, but accessed without MetaContext) ──
                    || "ab_i18n_resource".equals(tableName)           // Startup seeder writes tenantId=0 without context
                    || "ab_outbox".equals(tableName)                  // Outbox processor runs without tenant context
                    || "ab_scheduled_task".equals(tableName)          // Scheduler context, tenant_id NULLABLE
                    || "ab_scheduled_task_log".equals(tableName)      // Scheduler context, tenant_id NULLABLE
                    || "ab_notification_digest".equals(tableName)     // Scheduler flushes without tenant context
                    || "ab_async_task".equals(tableName)              // Thread pool execution without MetaContext
                    || "ab_sla_record".equals(tableName)              // Scheduler scans across all tenants every 60s
                    || "ab_automation".equals(tableName)              // Scheduler scans across all tenants every 60s/300s
                    || "ab_idempotency_record".equals(tableName)      // Scheduler cleanup runs across all tenants
                    || "ab_idempotent_key".equals(tableName)          // Scheduler cleanup runs across all tenants
                    || "ab_export_task".equals(tableName)             // @Async export + scheduler cleanup across tenants
                    || "ab_cloud_config".equals(tableName)            // PLATFORM-level rows have tenant_id=NULL
                    || "ab_invariant_definition".equals(tableName)    // InvariantAlarmWorker scans across all tenants in thread pool
                    || "ab_decision_definition".equals(tableName)     // DecisionAlarmWorker scans across all tenants in thread pool
                    || "ab_calendar_sync".equals(tableName)             // CalendarSyncJob scans across all tenants every 5min
                    || "ab_calendar_event_map".equals(tableName)        // CalendarSyncJob event mapping, tenant_id passed explicitly

                    // ── Mobile config (no tenant_id, no auth required) ──
                    || "ab_mobile_config".equals(tableName)
                    || "ab_mobile_client_log".equals(tableName)

                    // ── Email CRM (join tables without tenant_id) ──
                    || "ab_email_account_member".equals(tableName)    // Join table: account_id + user_id, no tenant_id

                    // ── CRM public inbound endpoint ──
                    // InboundController is a public (no-auth) endpoint; it resolves the tenant
                    // from the channel record itself and then sets MetaContext manually.
                    // The global PID lookup (findByPidGlobal) must bypass the tenant filter.
                    || "ab_inbound_channel".equals(tableName)

                    // ── External engines ──
                    || tableName.startsWith("se_")                    // SmartEngine BPM tables

                    // ── PostgreSQL system tables ──
                    || tableName.startsWith("information_schema.")
                    || "information_schema.tables".equals(tableName)
                 ;
            }
        });

        interceptor.addInnerInterceptor(tenantInterceptor);

        // env-layering PoC: second tenant-line interceptor reused with column=env_id, applied
        // ONLY to whitelisted @EnvScoped tables (whitelist via blacklist inversion). The
        // TenantLineHandler abstraction has no native whitelist — we invert ignoreTable.
        TenantLineInnerInterceptor envInterceptor = new TenantLineInnerInterceptor();
        envInterceptor.setTenantLineHandler(new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                Long envId = MetaContext.getCurrentEnvironmentId();
                // ignoreTable below short-circuits when envId == null, so this is only reached
                // with a real env id.
                return new LongValue(envId);
            }

            @Override
            public String getTenantIdColumn() {
                return "env_id";
            }

            @Override
            public boolean ignoreTable(String tableName) {
                if (MetaContext.isEnvFilterBypassed()) {
                    return true;  // promotion cross-env reads bypass intentionally
                }
                if (MetaContext.getCurrentEnvironmentId() == null) {
                    return true;  // no env context → don't filter (background tasks, legacy tests)
                }
                if (!envScopedTables().contains(tableName)) {
                    return true;  // not a DSL resource → don't apply env filter
                }
                return false;
            }
        });
        interceptor.addInnerInterceptor(envInterceptor);

        // Configure pagination with the correct database type
        DbType dbType = databaseDialect.getType() == DatabaseType.MYSQL
                ? DbType.MYSQL
                : DbType.POSTGRE_SQL;
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(dbType));

        return interceptor;
    }

}
