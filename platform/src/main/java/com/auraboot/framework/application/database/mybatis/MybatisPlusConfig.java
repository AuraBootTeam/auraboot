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

    /**
     * Table families owned by the composing host (e.g. {@code commerce_} for the
     * commerce storefront host). Host-owned families sit outside the platform
     * tenant-line surface entirely: the platform never appends tenant_id predicates
     * to them, and their mapper calls do not require a platform MetaContext. This is
     * an ownership declaration — the host is responsible for whatever scoping its
     * own tables need — so unlike {@code tenant-bypass-table-prefixes} it is not a
     * dev-profile affordance and is allowed in every profile. Fail-closed default
     * (empty = no host families declared).
     */
    @Value("${aura.persistence.host-table-prefixes:}")
    private String hostTablePrefixes = "";

    /** Static cache populated on first access. Drop-in replacement for the prior hardcoded Set. */
    private static volatile Set<String> envScopedTables;

    /**
     * Tenant-table registry (schema-verified 2026-09-27 against
     * db/snapshots/schema-current.sql; campaign plan: team docs
     * 2026-09-27-tenant-exemption-cleanup-plan). The ignoreTable verdict may only
     * come from these three sets:
     * <ul>
     *   <li>{@link #VERIFIED_GLOBAL_TABLES} — no tenant_id column; never shrinks.</li>
     *   <li>{@link #AUTH_PLANE_TABLES} — HAS a tenant_id column, but membership/role
     *       binding is queried across tenants by design (login, admission, bootstrap,
     *       cross-tenant authorization); the query parameters ARE the scope. Every
     *       pre-context seam is wrapped in {@link MetaContext#runWithoutTenantFilter};
     *       new queries on these tables must keep explicit identifiers. Reviewed as
     *       part of the W5 gate.</li>
     *   <li>{@link #MIGRATION_PENDING_TABLES} — must shrink to empty; each removal
     *       lands with its call-site migration (see W2a/W3e).</li>
     * </ul>
     * Adding a name back without schema evidence or an approved A4-class census is
     * forbidden.
     */
    public static final Set<String> VERIFIED_GLOBAL_TABLES = Set.of(
        // schema-verified: no tenant_id column (17)
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
        "ab_login_channel_auth_method",     // parent channel is explicit
        "ab_auth_appearance_state",         // deployment-level singleton; schema has no tenant_id
        "ab_auth_appearance_revision"       // deployment-level release audit; schema has no tenant_id
    );

    /**
     * Auth-plane tables (A4 census 2026-10-01, owner-approved tier): rows carry a
     * tenant_id but the binding itself is cross-tenant by design — "which tenants
     * does this user belong to", "which roles in which tenants", "is this user a
     * platform admin anywhere". The query parameters are the scope; automatic
     * injection of the caller's tenant cannot express that (and the decisive seams
     * run before any caller context exists). All pre-context seams are wrapped in
     * {@link MetaContext#runWithoutTenantFilter}. Owner-approved as a permanent
     * registry tier (W5, 2026-10-01).
     */
    public static final Set<String> AUTH_PLANE_TABLES = Set.of(
        "ab_tenant_member",                 // user↔tenant membership (login, admission, bootstrap)
        "ab_user_role",                     // member↔role binding (auth-filter role load, explicit tenantId)
        "ab_role"                           // role definitions (login + initializers pass tenantId explicitly)
    );

    /**
     * The migration ledger — kept as the W5 terminal gate. It closed at EMPTY on
     * 2026-10-01 (W2a–W3e): every table that has a tenant_id column but needs an
     * exemption is either in {@link #AUTH_PLANE_TABLES} (owner-approved tier) or
     * was removed outright. Do not add entries here; new exemptions require an
     * approved A4-class census and a new registry tier.
     */
    public static final Set<String> MIGRATION_PENDING_TABLES = Set.of();

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
                // ── Tenant-table registry (schema-verified; see set javadoc) ──
                if (VERIFIED_GLOBAL_TABLES.contains(tableName) || MIGRATION_PENDING_TABLES.contains(tableName)
                        || AUTH_PLANE_TABLES.contains(tableName)) {
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

                // ── Host-owned table families (B16 platform host contract) ──
                if (hasConfiguredHostPrefix(tableName)) {
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

    private boolean hasConfiguredHostPrefix(String tableName) {
        if (tableName == null || hostTablePrefixes == null || hostTablePrefixes.isBlank()) return false;
        if (!hostPrefixesLogged) {
            hostPrefixesLogged = true;
            log.info("Platform host-owned table prefixes active: {}", hostTablePrefixes);
        }
        return java.util.Arrays.stream(hostTablePrefixes.split(","))
                .map(String::trim)
                .filter(prefix -> !prefix.isEmpty())
                .anyMatch(tableName::startsWith);
    }

    private volatile boolean hostPrefixesLogged;

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
