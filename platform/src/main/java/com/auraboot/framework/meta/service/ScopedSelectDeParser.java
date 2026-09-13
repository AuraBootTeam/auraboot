package com.auraboot.framework.meta.service;

import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.util.deparser.ExpressionDeParser;
import net.sf.jsqlparser.util.deparser.SelectDeParser;
import org.springframework.security.access.AccessDeniedException;
import java.util.*;

/** Traverses nonrecursive CTEs in SQL lexical order before exposing their names. */
public class ScopedSelectDeParser extends SelectDeParser {
    private final Deque<Set<String>> frames = new ArrayDeque<>();

    public ScopedSelectDeParser(ExpressionDeParser expressions, StringBuilder output) {
        super(expressions, output);
    }

    @Override public <S> StringBuilder visit(PlainSelect select, S context) {
        frames.push(new HashSet<>());
        try { return super.visit(select, context); }
        finally { frames.pop(); }
    }
    @Override public <S> StringBuilder visit(ParenthesedSelect select, S context) {
        frames.push(new HashSet<>());
        try { return super.visit(select, context); }
        finally { frames.pop(); }
    }
    @Override public <S> StringBuilder visit(SetOperationList select, S context) {
        frames.push(new HashSet<>());
        try { return super.visit(select, context); }
        finally { frames.pop(); }
    }
    @Override public <S> StringBuilder visit(WithItem<?> item, S context) {
        if (item.isRecursive() || !(item.getParenthesedStatement() instanceof ParenthesedSelect))
            throw new AccessDeniedException("Only nonrecursive SELECT CTE sources are supported");
        String name = identifier(item.getAliasName());
        if (frames.isEmpty() || frames.peek().contains(name))
            throw new AccessDeniedException("Ambiguous CTE scope");
        StringBuilder result = super.visit(item, context);
        frames.peek().add(name);
        return result;
    }
    protected boolean isCte(Table table) {
        if (table.getSchemaName() != null) return false;
        String name = identifier(table.getName());
        return frames.stream().anyMatch(frame -> frame.contains(name));
    }
    public static String identifier(String value) {
        if (value == null) return "";
        if (value.startsWith("\"") && value.endsWith("\""))
            return value.substring(1, value.length() - 1).replace("\"\"", "\"");
        return value.toLowerCase(Locale.ROOT);
    }
}
