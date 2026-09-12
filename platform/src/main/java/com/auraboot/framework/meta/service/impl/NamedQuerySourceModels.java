package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.constant.SystemFieldConstants;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.SecureSqlRewriter;
import lombok.RequiredArgsConstructor;
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
    private static final Pattern IDENTIFIER = Pattern.compile("\"(?:[^\"]|\"\")+\"|[A-Za-z_][A-Za-z0-9_$]*");

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
        for (String table : sql.referencedTables("SELECT " + projections + " FROM " + source))
            resolveRelation(identity(table), catalog, result, views, new HashSet<>());
        return new Sources(result, views);
    }

    private void resolveRelation(String key, Map<String, Set<String>> catalog, Map<String, String> result,
                                 Map<String, String> views, Set<String> visiting) {
        if (visiting.contains(key)) throw new AccessDeniedException("Recursive view source is unsupported");
        if (result.containsKey(key)) return;
        if (visiting.size() >= 32) throw new AccessDeniedException("View source nesting exceeds the supported depth");
        Set<String> candidates = catalog.getOrDefault(key, Set.of());
        if (candidates.size() != 1) throw new AccessDeniedException("Export source model is unknown or ambiguous");
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
