package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.entity.NamedQueryField;
import com.auraboot.framework.meta.exception.MetaServiceException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.*;
import java.util.*;

/** Resolves SQL output dependencies without pretending unresolved sources are unprotected. */
final class NamedQueryColumnLineage {
    record PhysicalColumn(String table, String column) { }
    record Origin(Set<PhysicalColumn> columns, boolean direct, boolean opaqueFunction) {
        Origin { columns = Set.copyOf(columns); }
    }
    private interface Projection {
        Origin resolve(String column);
        default List<Origin> ordered() { throw unresolved("CTE column aliases require explicit projections"); }
    }
    private record Source(String qualifier, Projection projection) { }

    Map<String, Origin> resolve(String fromSql, List<NamedQueryField> fields) {
        try {
            String source = fromSql.trim();
            if (NamedQuerySqlSource.isQuery(source)) source = "(" + source + ") _nq";
            String outputs = String.join(", ", fields.stream()
                    .map(field -> field.getColumnExpr() + " AS " + field.getFieldCode()).toList());
            String sql = ("SELECT " + outputs + " FROM " + source)
                    .replaceAll("#\\{params\\.[a-zA-Z0-9_]+}", "?");
            Projection projection = projection((Select) CCJSqlParserUtil.parse(sql), Map.of());
            Map<String, Origin> result = new LinkedHashMap<>();
            for (NamedQueryField field : fields) {
                if (result.putIfAbsent(field.getFieldCode(), projection.resolve(identifier(field.getFieldCode()))) != null)
                    throw unresolved("Duplicate output alias");
            }
            return Map.copyOf(result);
        } catch (net.sf.jsqlparser.JSQLParserException error) {
            throw new MetaServiceException("Cannot resolve named query column sources", error);
        }
    }

    private Projection projection(Select select, Map<String, Projection> inherited) {
        Map<String, Projection> ctes = new LinkedHashMap<>(inherited);
        Set<String> localNames = new HashSet<>();
        if (select.getWithItemsList() != null) for (WithItem<?> item : select.getWithItemsList()) {
            if (item.isRecursive() || !(item.getParenthesedStatement() instanceof ParenthesedSelect body))
                throw unresolved("Only nonrecursive SELECT CTE sources are supported");
            String name = identifier(item.getAliasName());
            if (!localNames.add(name)) throw unresolved("Duplicate CTE name");
            Projection resolved = projection(body, ctes);
            if (item.getWithItemList() != null && !item.getWithItemList().isEmpty()) {
                List<Origin> ordered = resolved.ordered();
                if (item.getWithItemList().size() > ordered.size()) throw unresolved("Too many CTE column aliases");
                Map<String, Origin> renamed = new LinkedHashMap<>();
                // Partial lists retain original names in SQL; require a complete list to avoid guessing.
                if (item.getWithItemList().size() != ordered.size()) throw unresolved("Partial CTE column aliases require explicit resolution");
                for (int i = 0; i < ordered.size(); i++) {
                    Expression alias = item.getWithItemList().get(i).getExpression();
                    if (!(alias instanceof Column column)) throw unresolved("Invalid CTE column alias");
                    if (renamed.putIfAbsent(identifier(column.getColumnName()), ordered.get(i)) != null)
                        throw unresolved("Duplicate CTE column alias");
                }
                resolved = new Projection() {
                    public Origin resolve(String column) {
                        Origin origin = renamed.get(column);
                        if (origin == null) throw unresolved("Unknown CTE column: " + column);
                        return origin;
                    }
                    public List<Origin> ordered() { return List.copyOf(renamed.values()); }
                };
            }
            ctes.put(name, resolved);
        }
        if (select instanceof ParenthesedSelect nested) return projection(nested.getSelect(), ctes);
        if (!(select instanceof PlainSelect plain)) throw unresolved("Set-operation column sources require explicit resolution");
        List<Source> sources = new ArrayList<>();
        if (plain.getFromItem() != null) sources.add(source(plain.getFromItem(), ctes));
        if (plain.getJoins() != null) for (Join join : plain.getJoins()) sources.add(source(join.getRightItem(), ctes));
        Map<String, Origin> outputs = new LinkedHashMap<>();
        List<String> wildcards = new ArrayList<>();
        for (SelectItem<?> item : plain.getSelectItems()) {
            Expression expression = item.getExpression();
            if (expression instanceof AllTableColumns all) {
                wildcards.add(identifier(all.getTable().getName()));
                continue;
            }
            if (expression instanceof AllColumns) {
                wildcards.add("");
                continue;
            }
            String alias = item.getAlias() != null ? identifier(item.getAlias().getName())
                    : expression instanceof Column column ? identifier(column.getColumnName()) : null;
            if (alias == null) throw unresolved("Computed projection requires an alias");
            if (outputs.putIfAbsent(alias, expression(expression, sources)) != null)
                throw unresolved("Ambiguous output alias: " + alias);
        }
        return new Projection() {
            public Origin resolve(String column) {
                Origin known = outputs.get(column);
                if (known != null) return known;
                if (wildcards.size() != 1) throw unresolved("Unknown or ambiguous projected column: " + column);
                return column(column, wildcards.get(0), sources);
            }
            public List<Origin> ordered() {
                if (!wildcards.isEmpty()) return Projection.super.ordered();
                return List.copyOf(outputs.values());
            }
        };
    }

    private Source source(FromItem item, Map<String, Projection> ctes) {
        String alias = item.getAlias() == null ? null : identifier(item.getAlias().getName());
        if (item instanceof Table table) {
            String tableName = table.getFullyQualifiedName();
            Projection cte = table.getSchemaName() == null ? ctes.get(identifier(table.getName())) : null;
            if (cte != null) return new Source(alias != null ? alias : identifier(table.getName()), cte);
            return new Source(alias != null ? alias : identifier(table.getName()),
                    column -> new Origin(Set.of(new PhysicalColumn(tableName, column)), true, false));
        }
        if (item instanceof ParenthesedSelect nested) {
            if (alias == null) throw unresolved("Derived source requires an alias");
            return new Source(alias, projection(nested, ctes));
        }
        throw unresolved("Unsupported FROM source");
    }

    private Origin expression(Expression expression, List<Source> sources) {
        if (expression instanceof Column column) return column(identifier(column.getColumnName()),
                column.getTable() == null ? "" : identifier(column.getTable().getName()), sources);
        Set<PhysicalColumn> dependencies = new LinkedHashSet<>();
        boolean[] opaque = {false};
        expression.accept(new ExpressionVisitorAdapter<Void>() {
            @Override public <S> Void visit(Column value, S context) {
                Origin origin = column(identifier(value.getColumnName()),
                        value.getTable() == null ? "" : identifier(value.getTable().getName()), sources);
                dependencies.addAll(origin.columns());
                opaque[0] |= origin.opaqueFunction();
                return null;
            }
            @Override public <S> Void visit(Function value, S context) {
                opaque[0] = true;
                return super.visit(value, context);
            }
            @Override public <S> Void visit(Select value, S context) {
                throw unresolved("Scalar subquery requires explicit resolution");
            }
            @Override public <S> Void visit(ParenthesedSelect value, S context) {
                throw unresolved("Scalar subquery requires explicit resolution");
            }
            @Override public <S> Void visit(AllColumns value, S context) {
                throw unresolved("Wildcard expression requires model field expansion");
            }
        }, null);
        return new Origin(dependencies, false, opaque[0]);
    }

    private Origin column(String name, String qualifier, List<Source> sources) {
        List<Source> matching = qualifier.isEmpty() ? sources
                : sources.stream().filter(source -> source.qualifier().equals(qualifier)).toList();
        if (matching.size() != 1) throw unresolved("Unresolved or ambiguous column: " + name);
        return matching.get(0).projection().resolve(name);
    }

    private static String identifier(String value) {
        if (value == null) return "";
        if (value.startsWith("\"") && value.endsWith("\"")) return value.substring(1, value.length() - 1).replace("\"\"", "\"");
        return value.toLowerCase(Locale.ROOT);
    }
    private static MetaServiceException unresolved(String message) {
        return new MetaServiceException("Cannot resolve named query column sources: " + message);
    }
}
