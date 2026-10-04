package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.entity.NamedQueryField;
import com.auraboot.framework.meta.security.SqlSafetyUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bound list search; field/source protection still rewrites the complete query afterwards. */
final class NamedQueryKeywordFilter {
    private NamedQueryKeywordFilter() {}

    static void append(String keyword, List<NamedQueryField> fields,
                       List<String> clauses, Map<String, Object> params) {
        append(keyword, fields, clauses, params, Set.of());
    }

    static void append(String keyword, List<NamedQueryField> fields,
                       List<String> clauses, Map<String, Object> params, Set<String> maskedAliases) {
        if (keyword == null || keyword.isBlank()) return;
        List<String> searches = new ArrayList<>();
        for (NamedQueryField field : fields) {
            if (!Boolean.TRUE.equals(field.getSearchable()) || maskedAliases.contains(field.getFieldCode())) continue;
            if (field.hasOperators() && !field.supportsOperator("like")
                    && !field.supportsOperator("ilike") && !field.supportsOperator("contains")) continue;
            SqlSafetyUtils.validateSqlFragment(field.getColumnExpr());
            searches.add("CAST(" + field.getColumnExpr()
                    + " AS TEXT) ILIKE #{params.__aura_nq_keyword} ESCAPE E'\\\\'");
        }
        if (searches.isEmpty()) {
            // A query without authorized searchable fields must not silently return every row.
            clauses.add("1 = 0");
            return;
        }
        String literal = keyword.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        params.put("__aura_nq_keyword", "%" + literal + "%");
        clauses.add("(" + String.join(" OR ", searches) + ")");
    }
}
