package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.exception.MetaServiceException;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.util.deparser.ExpressionDeParser;
import net.sf.jsqlparser.util.deparser.SelectDeParser;
import org.springframework.security.access.AccessDeniedException;
import java.util.*;
import java.util.regex.Pattern;

/** Applies row scopes at physical sources, preserving join null-extension semantics. */
final class NamedQuerySourceScopeRewriter {
    private static final Pattern PARAMETER = Pattern.compile("#\\{params\\.[A-Za-z0-9_]+\\}");

    String rewrite(String sql, Map<String, String> scopes) {
        if (scopes.values().stream().allMatch(String::isBlank)) return sql;
        Map<String, String> bindings = new LinkedHashMap<>();
        var matcher = PARAMETER.matcher(sql);
        StringBuffer normalized = new StringBuffer();
        while (matcher.find()) {
            String token = ":__nq_scope_parameter_" + bindings.size();
            if (sql.contains(token)) throw new AccessDeniedException("Reserved source scope parameter");
            bindings.put(token, matcher.group());
            matcher.appendReplacement(normalized, java.util.regex.Matcher.quoteReplacement(token));
        }
        matcher.appendTail(normalized);
        try {
            var parsed = CCJSqlParserUtil.parse(normalized.toString());
            if (!(parsed instanceof Select select)) throw new AccessDeniedException("Source scope requires SELECT");
            StringBuilder output = new StringBuilder();
            ExpressionDeParser expressions = new ExpressionDeParser(null, output) {
                @Override public <S> StringBuilder visit(AllTableColumns columns, S context) {
                    Table qualifier = columns.getTable();
                    if (qualifier != null && qualifier.getSchemaName() != null
                            && !scopes.getOrDefault(NamedQuerySourceModels.identity(qualifier.getFullyQualifiedName()), "").isBlank()) {
                        columns.setTable(new Table(qualifier.getName()));
                    }
                    return super.visit(columns, context);
                }
                @Override public <S> StringBuilder visit(Column column, S context) {
                    Table qualifier = column.getTable();
                    if (qualifier != null && qualifier.getSchemaName() != null
                            && !scopes.getOrDefault(NamedQuerySourceModels.identity(qualifier.getFullyQualifiedName()), "").isBlank()) {
                        column.setTable(new Table(qualifier.getName()));
                    }
                    return super.visit(column, context);
                }
            };
            SelectDeParser selects = new SelectDeParser(expressions, output) {
                @Override public <S> StringBuilder visit(Table table, S context) {
                    String identity = NamedQuerySourceModels.identity(table.getFullyQualifiedName());
                    if (!scopes.containsKey(identity)) throw new AccessDeniedException("Unresolved scoped query source");
                    String condition = scopes.get(identity);
                    if (condition.isBlank()) return super.visit(table, context);
                    if (table.getPivot() != null || table.getUnPivot() != null || table.getSampleClause() != null || table.getIndexHint() != null)
                        throw new AccessDeniedException("Scoped source modifiers require explicit support");
                    output.append("(SELECT * FROM ").append(table.getFullyQualifiedName())
                            .append(" WHERE (").append(condition).append("))");
                    output.append(table.getAlias() != null ? table.getAlias().toString() : " AS " + table.getName());
                    return output;
                }
                @Override public <S> StringBuilder visit(WithItem<?> item, S context) {
                    throw new AccessDeniedException("Scoped CTE sources require explicit resolution");
                }
                @Override public <S> StringBuilder visit(TableFunction function, S context) {
                    throw new AccessDeniedException("Scoped table functions require explicit resolution");
                }
            };
            expressions.setSelectVisitor(selects);
            select.accept((SelectVisitor<StringBuilder>) selects, null);
            String rewritten = output.toString();
            List<String> keys = new ArrayList<>(bindings.keySet());
            keys.sort(Comparator.comparingInt(String::length).reversed());
            for (String key : keys) rewritten = rewritten.replace(key, bindings.get(key));
            return rewritten;
        } catch (JSQLParserException invalid) {
            throw new MetaServiceException("Cannot apply named query source scopes", invalid);
        }
    }
}
