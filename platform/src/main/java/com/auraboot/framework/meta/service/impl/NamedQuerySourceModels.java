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
    private static final Pattern IDENTIFIER = Pattern.compile("\"(?:[^\"]|\"\")+\"|[A-Za-z_][A-Za-z0-9_$]*");

    Map<String, String> resolve(Long tenant, String fromSql, List<com.auraboot.framework.meta.entity.NamedQueryField> fields) {
        if (tenant == null) throw new AccessDeniedException("Export source requires a tenant");
        Map<String, Set<String>> catalog = new HashMap<>();
        for (var model : mapper.findCurrentForTenant(tenant)) {
            String table = model.getTableName();
            if (table == null || table.isBlank()) table = SystemFieldConstants.generateTableName(model.getCode());
            catalog.computeIfAbsent(identity(table), ignored -> new HashSet<>()).add(model.getCode());
        }
        String source = fromSql.trim();
        if (source.regionMatches(true, 0, "SELECT", 0, 6)) source = "(" + source + ") _nq";
        Map<String, String> result = new TreeMap<>();
        String projections = fields.stream().map(field -> field.getColumnExpr() + " AS " + field.getFieldCode()).collect(java.util.stream.Collectors.joining(", "));
        for (String table : sql.referencedTables("SELECT " + projections + " FROM " + source)) {
            String key = identity(table);
            Set<String> candidates = catalog.getOrDefault(key, Set.of());
            if (candidates.size() != 1) throw new AccessDeniedException("Export source model is unknown or ambiguous");
            result.put(key, candidates.iterator().next());
        }
        return Collections.unmodifiableMap(result);
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
