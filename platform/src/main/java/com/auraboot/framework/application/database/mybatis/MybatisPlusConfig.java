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
import org.springframework.beans.factory.annotation.Value;

import java.util.HashSet;
import java.util.Set;

@Slf4j
@Configuration
public class MybatisPlusConfig {

    @Value("${aura.persistence.tenant-bypass-table-prefixes:}")
    private String tenantBypassTablePrefixes = "";

    /** Static cache populated on first access. Drop-in replacement for the prior hardcoded Set. */
    private static volatile Set<String> envScopedTables;

    /**
     * Tenant-exemption registry (cleanup campaign, schema-verified 2026-09-27 against
     * db/snapshots/schema-current.sql). The ignoreTable verdict may only come from these
     * two sets: VERIFIED_GLOBAL shrinks never, MIGRATION_PENDING must shrink to empty —
     * each removal lands with its call-site migration to
     * {@link MetaContext#runWithoutTenantFilter} (see W2a: SessionManagementServiceImpl).
     * Adding a name back without a schema-verified "no tenant_id column" evidence row is
     * forbidden; the campaign plan lives in the team docs (2026-09-27-tenant-exemption-cleanup-plan).
     */
    public static final Set<String> VERIFIED_GLOBAL_TABLES = Set.of(
        // schema-verified: no tenant_id column (15)
        "ab_user",                          // global identity
        "ab_tenant",                        // the tenant registry itself
        "ab_system_config",                 // platform config
        "ab_bootstrap",                     // bootstrap state
        "ab_billing_account",               // billing spine, publisher-scoped
        "ab_api_connector_endpoint",        // parented by connector_pid
        "ab_jdbc_connector_endpoint",       // parented by connector_pid
        "ab_mkt_publisher_payout",          // publisher-scoped, not tenant-scoped
        "ab_email_account_member",          // join table account_id+user_id
        "ab_user_deactivation",
        "ab_user_social_link",
        "ab_verification_code",             // pre-auth OTP
        "ab_auth_identity",                 // WeChat identity lookup before tenant context
        "ab_login_application",             // pre-auth global application registry
        "ab_login_channel_auth_method"      // parent channel is explicit
    );

    /**
     * Tables that HAVE a tenant_id column (schema-verified) but are still exempted for
     * behavioral reasons. Every entry here is a scheduled migration: wrap the context-less
     * call sites in {@link MetaContext#runWithoutTenantFilter} and delete the entry.
     * Cluster notes: login/auth-seam (W2b/c/d), admin & entitlement (W4),
     * permission-audit @Async (W4), remaining scheduler/worker seams (W3:
     * outbox, scheduled-task pair), mobile config (W4 — schema shows tenant_id
     * despite the old "no tenant_id" comment). Done: W4a exchange-rate, W3a
     * idempotency/i18n/cloud seeders, W3b behavior trio + export/async
     * (@Async executors propagate MetaContext via TenantAwareTaskDecorator —
     * the old "@Async threads lack MetaContext" comments were wrong).
     */
    public static final Set<String> MIGRATION_PENDING_TABLES = Set.of(
        // W2 pending — auth seam / cross-tenant by design
        "ab_tenant_member",                 // "which tenants does user belong to"; SINGLE-mode filter lookup
        "ab_invitation",                    // pre-join invitation verified before tenant context
        "ab_login_channel",                 // pre-auth routing; tenant selector is explicit
        "ab_identity_provider_instance",    // pre-auth routing; tenant selector is explicit
        "ab_external_identity_link",        // identity lookup occurs before tenant context
        // W2b pending — RBAC pair (highest fan-out: explicit-param style spreads context-less
        // callers across initializers/listeners/caches; needs a dedicated census)
        "ab_user_role",                     // login + auth-filter role load pass tenantId explicitly
        "ab_role",                          // login + initializers pass tenantId explicitly
        // W4 pending — admin/entitlement explicit tenantId
        "ab_tenant_entitlement",
        "ab_license_audit_log",
        "ab_payment_order",
        "ab_payment_transaction",
        "ab_marketplace_solution_install",
        "ab_tenant_login_channel",          // queried by explicit tenantId before auth
        // W4 pending — @Async write without MetaContext
        "ab_permission_audit_log",
        // W3 pending — scheduler/worker/async executors without MetaContext
        "ab_outbox",                        // outbox processor runs without tenant context
        "ab_scheduled_task",                // scheduler context, tenant_id NULLABLE
        "ab_scheduled_task_log",            // scheduler context, tenant_id NULLABLE
        "ab_notification_digest",           // scheduler flushes without tenant context
        "ab_automation",                    // scheduler scans across all tenants every 60s/300s
        // W4 pending — mobile config (schema HAS tenant_id despite the old "no tenant_id" comment)
        "ab_mobile_config",
        "ab_mobile_client_log"
    );

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
                                                          ApplicationContext applicationContext,
                                                          org.springframework.core.env.Environment environment) {
        guardTenantBypassPrefixes(environment);
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
                // Explicit code-level scope replaces blanket table exemptions
                // (tenant-exemption cleanup W1): pre-auth lookups and system
                // workers wrap their queries in MetaContext.runWithoutTenantFilter
                // instead of the table being permanently exempt below.
                if (MetaContext.isTenantFilterBypassed()) {
                    return true;
                }
                // ── Tenant-exemption registry (schema-verified; see set javadoc) ──
                if (VERIFIED_GLOBAL_TABLES.contains(tableName) || MIGRATION_PENDING_TABLES.contains(tableName)) {
                    return true;
                }

                // ── Recursive CTE (not a real table) ──
                // "domain_tree": DataDomainMapper.findDescendantIds recursive CTE. The interceptor was
                // injecting "dt.tenant_id = ?" on the CTE alias → "column dt.tenant_id does not exist",
                // 500'ing getUserDomainIdsWithDescendants / buildDomainFilter / filterByDomain in
                // production (caught 2026-06-19 by the DataDomainServiceImpl coverage IT). The CTE's
                // anchor + recursive terms already filter tenant_id explicitly.
                if ("domain_tree".equals(tableName)) {
                    return true;
                }

                // ── Application-contributed external stores ──
                if (hasConfiguredBypassPrefix(tableName)) {
                    return true;
                }

                // ── PostgreSQL system tables ──
                return tableName.startsWith("information_schema.")
                    || "information_schema.tables".equals(tableName);
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

    private boolean hasConfiguredBypassPrefix(String tableName) {
        if (tableName == null || tenantBypassTablePrefixes == null) return false;
        return java.util.Arrays.stream(tenantBypassTablePrefixes.split(","))
                .map(String::trim)
                .filter(prefix -> !prefix.isEmpty())
                .anyMatch(tableName::startsWith);
    }

    /**
     * The prefix bypass turns the tenant filter off for whole table families. That is a
     * single-tenant/dev-store affordance; in any non-dev profile a configured bypass is a
     * cross-tenant leak waiting for its first query. Fail fast at startup instead.
     */
    public void guardTenantBypassPrefixes(org.springframework.core.env.Environment environment) {
        if (tenantBypassTablePrefixes == null || tenantBypassTablePrefixes.isBlank()) {
            return;
        }
        java.util.Set<String> active = java.util.Set.of(environment.getActiveProfiles());
        boolean devLike = active.stream().anyMatch(p ->
                p.equals("dev") || p.equals("local") || p.equals("test") || p.equals("integration-test"));
        if (!devLike) {
            throw new IllegalStateException(
                    "aura.persistence.tenant-bypass-table-prefixes is configured ('"
                    + tenantBypassTablePrefixes + "') under non-dev profiles " + active
                    + ". The prefix bypass disables tenant isolation for whole table families; "
                    + "it is only permitted in dev/local/test profiles.");
        }
        log.warn("Tenant bypass table prefixes active under dev-like profiles {}: {}",
                active, tenantBypassTablePrefixes);
    }

}
