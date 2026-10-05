package com.auraboot.framework.semantic.compiler;

import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.Select;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Column provenance for scalar author fragments, independent of SQL string literals. */
final class SemanticExpressionReferences {
    private SemanticExpressionReferences() { }

    static Set<String> resolve(String expression) {
        try {
            // false requires complete input consumption: no trailing SELECT clauses are ignored.
            var scalar = CCJSqlParserUtil.parseCondExpression(expression, false);
            Set<String> columns = new LinkedHashSet<>();
            scalar.accept(new ExpressionVisitorAdapter<Void>() {
                @Override public <S> Void visit(Column value, S context) {
                    String name = value.getColumnName();
                    if (name.startsWith("\"") && name.endsWith("\"")) name = name.substring(1, name.length() - 1);
                    else name = name.toLowerCase(Locale.ROOT);
                    if (!name.matches("[a-zA-Z_][a-zA-Z0-9_]*")) throw unresolved();
                    columns.add(name);
                    return null;
                }
                @Override public <S> Void visit(Select value, S context) { throw unresolved(); }
                @Override public <S> Void visit(ParenthesedSelect value, S context) { throw unresolved(); }
                @Override public <S> Void visit(AllColumns value, S context) { throw unresolved(); }
                @Override public <S> Void visit(AllTableColumns value, S context) { throw unresolved(); }
            }, null);
            return columns;
        } catch (net.sf.jsqlparser.JSQLParserException invalid) {
            throw unresolved();
        }
    }

    private static MetricCompileException unresolved() {
        return new MetricCompileException("UNRESOLVED_COLUMN_REFERENCE",
                "Semantic SQL expression has unresolved column provenance");
    }
}
