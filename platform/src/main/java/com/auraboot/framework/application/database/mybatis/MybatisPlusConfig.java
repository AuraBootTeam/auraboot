package com.auraboot.framework.application.database.mybatis;

import com.auraboot.framework.application.database.dialect.DatabaseDialect;
import com.auraboot.framework.application.database.dialect.DatabaseType;
import com.auraboot.framework.application.tenant.MetaContext;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(DatabaseDialect databaseDialect) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        TenantLineInnerInterceptor tenantInterceptor = new TenantLineInnerInterceptor();
        tenantInterceptor.setTenantLineHandler(new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                Long tenantId = MetaContext.getCurrentTenantId();
                if (tenantId == null) {
                    throw new IllegalStateException("Tenant context is required but not found");
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

        // Configure pagination with the correct database type
        DbType dbType = databaseDialect.getType() == DatabaseType.MYSQL
                ? DbType.MYSQL
                : DbType.POSTGRE_SQL;
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(dbType));

        return interceptor;
    }
}
