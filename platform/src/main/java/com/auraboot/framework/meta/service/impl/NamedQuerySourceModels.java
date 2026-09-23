package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.constant.SystemFieldConstants;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.SecureSqlRewriter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.regex.Pattern;

/** Resolves physical query sources against current tenant metadata. */
@Service
@RequiredArgsConstructor
public class NamedQuerySourceModels {
    private final MetaModelMapper mapper;
    private final SecureSqlRewriter sql;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Value("${aura.persistence.tenant-bypass-table-prefixes:se_}")
    private String tenantBypassTablePrefixes = "se_";
    private static final Pattern IDENTIFIER = Pattern.compile("\"(?:[^\"]|\"\")+\"|[A-Za-z_][A-Za-z0-9_$]*");

    /**
     * Platform reference sources resolvable without a tenant model, as qualified identities.
     *
     * <p>Admission rule (see #1968 for ab_tenant): platform-owned tables that the named
     * query joins on their unique pid (or with an explicit tenant filter) against an
     * already tenant-scoped anchor, so no cross-tenant rows can leak through the join.
     * ab_file and ab_async_task carry tenant_id and the quoting named queries filter them
     * by #{params.tenantId} / the anchor's tenant explicitly.</p>
     *
     * <p>ab_user_role / ab_role_permission / ab_permission (owner security sign-off
     * 2026-09-20): all three carry tenant_id, and the people-workload named queries join
     * each of them with an explicit {@code tenant_id = <anchor tenant>} filter plus
     * soft-delete predicates, chained off the tenant-scoped member/role anchor rows —
     * the workload tooling pages were fully denied without this admission.</p>
     *
     * <p>ab_tenant_member (2026-09-21): the tenant member directory joined by the
     * quote/BOM dashboard chart queries strictly on its unique pid (or an explicit
     * tenant filter) off tenant-scoped anchors, purely to resolve display names.
     * It is the same identity-registry class as ab_user; without the admission every
     * dashboard chart-data request was denied for business roles (107 gate evidence:
     * 1004+ denied executions, all quote/BOM role journeys showing "Access forbidden").</p>
     */
    private static final Set<String> PLATFORM_REFERENCE_SOURCES =
            Set.of("\"public\".\"ab_user\"", "\"public\".\"ab_tenant\"",
                    "\"public\".\"ab_file\"", "\"public\".\"ab_async_task\"",
                    "\"public\".\"ab_user_role\"", "\"public\".\"ab_role_permission\"",
                    "\"public\".\"ab_permission\"", "\"public\".\"ab_tenant_member\"");
    /** Platform-owned tables that have no tenant model but must still receive a tenant scope. */
    private static final Set<String> TENANT_SCOPED_PLATFORM_SOURCES =
            Set.of("\"public\".\"ab_named_query\"");
    /** Marker model code for a platform reference source; protection must skip model checks. */
    public static final String PLATFORM_REFERENCE_MARKER = "platform.reference";
    /** Marker prefix for engine tables under the tenant-bypass prefixes (own tenant_id column). */
    public static final String ENGINE_SOURCE_MARKER_PREFIX = "engine.";

    static String platformReferenceMarker(String key) {
        return PLATFORM_REFERENCE_MARKER;
    }

    /** Marker for an engine-table source under the tenant-bypass prefixes (own tenant_id column). */
    static String engineSourceMarker(String key) {
        String bare = key.trim().toLowerCase();
        int lastDot = bare.lastIndexOf('.');
        if (lastDot >= 0) bare = bare.substring(lastDot + 1);
        bare = bare.replace("\"", "");
        return ENGINE_SOURCE_MARKER_PREFIX + bare;
    }

    /** True when the quoted identity's bare table name starts with an engine (bypass) prefix. */
    boolean isBypassEngineSource(String key) {
        String bare = key.trim().toLowerCase();
        int lastDot = bare.lastIndexOf('.');
        if (lastDot >= 0) bare = bare.substring(lastDot + 1);
        bare = bare.replace("\"", "");
        return Arrays.stream(tenantBypassTablePrefixes.split(","))
                .map(String::trim)
                .filter(prefix -> !prefix.isEmpty())
                .anyMatch(bare::startsWith);
    }

    record Sources(Map<String, String> models, Map<String, String> views) {
        Sources { models = Map.copyOf(models); views = Map.copyOf(views); }
    }

    Map<String, String> resolve(Long tenant, String fromSql, List<com.auraboot.framework.meta.entity.NamedQueryField> fields) {
        return resolvePlan(tenant, fromSql, fields).models();
    }

    Sources resolvePlan(Long tenant, String fromSql, List<com.auraboot.framework.meta.entity.NamedQueryField> fields) {
        if (tenant == null || tenant <= 0) throw new AccessDeniedException("Export source requires a tenant");
        Map<String, Set<String>> catalog = new HashMap<>();
        for (var model : mapper.findCurrentForTenant(tenant)) {
            String table = "sqlView".equals(model.getSourceType()) ? model.getSourceRef() : model.getTableName();
            if (table == null || table.isBlank()) table = SystemFieldConstants.generateTableName(model.getCode());
            catalog.computeIfAbsent(identity(table), ignored -> new HashSet<>()).add(model.getCode());
        }
        String source = fromSql.trim();
        if (NamedQuerySqlSource.isQuery(source)) source = "(" + source + ") _nq";
        Map<String, String> result = new TreeMap<>();
        Map<String, String> views = new TreeMap<>();
        String projections = fields.stream().map(field -> field.getColumnExpr() + " AS " + field.getFieldCode()).collect(java.util.stream.Collectors.joining(", "));
        // Queries without declared output fields cannot project a column list; a star
        // projection keeps source resolution working for field-less sources.
        String projectionClause = projections.isBlank() ? "*" : projections;
        for (String table : sql.referencedTables("SELECT " + projectionClause + " FROM " + source))
            resolveRelation(identity(table), catalog, result, views, new HashSet<>());
        return new Sources(result, views);
    }

    private void resolveRelation(String key, Map<String, Set<String>> catalog, Map<String, String> result,
                                 Map<String, String> views, Set<String> visiting) {
        if (visiting.contains(key)) throw new AccessDeniedException("Recursive view source is unsupported");
        if (result.containsKey(key)) return;
        if (visiting.size() >= 32) throw new AccessDeniedException("View source nesting exceeds the supported depth");
        if (PLATFORM_REFERENCE_SOURCES.contains(key)) {
            // Platform reference tables (identity and similar global registries) have no tenant
            // column and no tenant model, so they cannot take row scopes or field protections.
            // They are only ever joined on their unique pid against an already tenant-scoped
            // anchor (e.g. ab_user.pid = activity owner), which exposes no rows beyond that
            // anchor's scope. Mapping them to a reserved marker lets protection skip them.
            result.put(key, platformReferenceMarker(key));
            return;
        }
        if (TENANT_SCOPED_PLATFORM_SOURCES.contains(key)) {
            // Platform metadata such as ab_named_query is not represented by a tenant
            // meta-model, but it does carry tenant_id. Reuse the scoped system marker so
            // field protection always injects the current tenant instead of either
            // rejecting the source or admitting it with the unscoped reference marker.
            result.put(key, engineSourceMarker(key));
            return;
        }
        if (isBypassEngineSource(key)) {
            // Engine tables (se_*) sit under the configured tenant-bypass prefixes: the
            // runtime maintains their tenant_id column and NQ SQL on them carries explicit
            // tenant filters, so they cannot back meta-model source resolution.
            result.put(key, engineSourceMarker(key));
            return;
        }
        Set<String> candidates = catalog.getOrDefault(key, Set.of());
        if (candidates.size() != 1) throw new AccessDeniedException(
                "Export source model is unknown or ambiguous: " + key + " (candidates=" + candidates.size() + ")");
        Map<String, Object> relation = jdbc.queryForMap("""
                SELECT c.relkind::text AS kind,
                    EXISTS(SELECT 1 FROM pg_catalog.pg_attribute a WHERE a.attrelid=c.oid
                        AND a.attname='tenant_id' AND a.attnum>0 AND NOT a.attisdropped) AS tenant_column,
                    CASE WHEN c.relkind='v' THEN pg_catalog.pg_get_viewdef(c.oid, false) ELSE '' END AS definition
                FROM pg_catalog.pg_class c WHERE c.oid=pg_catalog.to_regclass(?)
                """, key);
        if (!Boolean.TRUE.equals(relation.get("tenant_column")))
            throw new AccessDeniedException("Named query source has no tenant isolation column");
        String kind = String.valueOf(relation.get("kind"));
        if (!Set.of("r", "p", "v").contains(kind))
            throw new AccessDeniedException("Named query relation kind requires explicit source semantics");
        result.put(key, candidates.iterator().next());
        if ("v".equals(kind)) {
            String definition = String.valueOf(relation.get("definition")).trim().replaceFirst(";\\s*$", "");
            if (definition.isBlank()) throw new AccessDeniedException("View definition is unavailable");
            views.put(key, definition);
            visiting.add(key);
            try {
                for (String child : sql.referencedTables(definition))
                    resolveRelation(identity(child), catalog, result, views, visiting);
            } finally { visiting.remove(key); }
        }
    }

    static String identity(String table) {
        var matcher = IDENTIFIER.matcher(table.trim());
        List<String> parts = new ArrayList<>();
        int end = 0;
        String text = table.trim();
        while (matcher.find()) {
            String separator = text.substring(end, matcher.start()).trim();
            if (!(parts.isEmpty() ? separator.isEmpty() : separator.equals(".")))
                throw new AccessDeniedException("Unsupported export source identifier");
            String part = matcher.group();
            parts.add(part.startsWith("\"") ? part.substring(1, part.length() - 1).replace("\"\"", "\"") : part.toLowerCase(Locale.ROOT));
            end = matcher.end();
        }
        if (end != text.length() || parts.isEmpty() || parts.size() > 2)
            throw new AccessDeniedException("Unsupported export source identifier");
        if (parts.size() == 1) parts.add(0, "public");
        return parts.stream().map(part -> "\"" + part.replace("\"", "\"\"") + "\"").collect(java.util.stream.Collectors.joining("."));
    }
}
