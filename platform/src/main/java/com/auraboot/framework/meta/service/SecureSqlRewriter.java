package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.exception.MetaServiceException;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 安全SQL重写器
 * 
 * 使用JSqlParser替代正则表达式进行SQL重写，支持：
 * - 多行SELECT语句
 * - 子查询
 * - 复杂WHERE条件
 * - JOIN语句
 * 
 * @author AuraBoot Team
 * @since 2.1.0
 */
@Slf4j
@Service
public class SecureSqlRewriter {

    private static final Pattern MYBATIS_PARAM_PATTERN =
            Pattern.compile("#\\{params\\.([a-zA-Z0-9_]+)\\}");

    private static class NormalizedSql {
        private final String sql;
        private final Map<String, String> placeholderMap;

        private NormalizedSql(String sql, Map<String, String> placeholderMap) {
            this.sql = sql;
            this.placeholderMap = placeholderMap;
        }
    }
    
    /**
     * 将SELECT语句重写为COUNT查询
     * 
     * @param originalSql 原始SELECT语句
     * @return COUNT查询SQL
     * @throws MetaServiceException 如果SQL解析失败
     */
    public String rewriteForCount(String originalSql) {
        if (originalSql == null || originalSql.trim().isEmpty()) {
            throw new IllegalArgumentException("Original SQL cannot be null or empty");
        }
        
        try {
            log.debug("Rewriting SQL for COUNT: {}", originalSql);

            NormalizedSql normalized = normalizeMybatisParams(originalSql);
            Statement stmt = CCJSqlParserUtil.parse(normalized.sql);
            
            if (!(stmt instanceof Select)) {
                throw new IllegalArgumentException("Not a SELECT statement: " + originalSql);
            }
            
            Select select = (Select) stmt;
            
            // 获取SelectBody - 使用新API
            if (select.getPlainSelect() != null) {
                PlainSelect plainSelect = select.getPlainSelect();
                
                // 创建COUNT(*)函数
                Function countFunction = new Function();
                countFunction.setName("count");
                countFunction.setParameters(new ExpressionList<>(List.of(new AllColumns())));
                
                // 创建SelectItem列表
                List<SelectItem<?>> selectItems = new ArrayList<>();
                SelectItem<?> countItem = new SelectItem<>(countFunction);
                selectItems.add(countItem);
                
                // 替换SELECT项为COUNT(*)
                plainSelect.setSelectItems(selectItems);
                
                // 移除ORDER BY子句（COUNT查询不需要排序）
                plainSelect.setOrderByElements(null);
                
                // 移除LIMIT和OFFSET（COUNT查询不需要分页）
                plainSelect.setLimit(null);
                plainSelect.setOffset(null);
                
                String rewrittenSql = select.toString();
                rewrittenSql = restoreMybatisParams(rewrittenSql, normalized.placeholderMap);
                log.debug("Rewritten COUNT SQL: {}", rewrittenSql);
                
                return rewrittenSql;
                
            } else {
                // 对于复杂查询（UNION等），将整个查询包装在子查询中
                String wrappedSql = "SELECT COUNT(*) FROM (" + originalSql + ") AS count_subquery";
                log.debug("Wrapped complex query for COUNT: {}", wrappedSql);
                return wrappedSql;
            }
            
        } catch (JSQLParserException e) {
            log.error("Failed to parse SQL for COUNT rewrite: {}", originalSql, e);
            throw new MetaServiceException("SQL parse failed: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Failed to rewrite SQL for COUNT: {}", originalSql, e);
            throw new MetaServiceException("SQL rewrite failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * 将SELECT语句重写为DELETE语句
     * 
     * @param selectSql 原始SELECT语句
     * @param tableName 表名
     * @return DELETE语句SQL
     * @throws MetaServiceException 如果SQL解析失败
     */
    public String rewriteForDelete(String selectSql, String tableName) {
        if (selectSql == null || selectSql.trim().isEmpty()) {
            throw new IllegalArgumentException("Select SQL cannot be null or empty");
        }
        if (tableName == null || tableName.trim().isEmpty()) {
            throw new IllegalArgumentException("Table name cannot be null or empty");
        }
        
        try {
            log.debug("Rewriting SQL for DELETE from table {}: {}", tableName, selectSql);

            NormalizedSql normalized = normalizeMybatisParams(selectSql);
            Statement stmt = CCJSqlParserUtil.parse(normalized.sql);
            
            if (!(stmt instanceof Select)) {
                throw new IllegalArgumentException("Not a SELECT statement: " + selectSql);
            }
            
            Select select = (Select) stmt;
            
            if (select.getPlainSelect() == null) {
                throw new IllegalArgumentException("Only PlainSelect is supported for DELETE rewrite");
            }
            
            PlainSelect plainSelect = select.getPlainSelect();
            
            // 验证FROM子句中的表名匹配
            if (plainSelect.getFromItem() instanceof Table) {
                Table fromTable = (Table) plainSelect.getFromItem();
                if (!tableName.equalsIgnoreCase(fromTable.getName())) {
                    log.warn("Table name mismatch: expected {}, found {}", 
                            tableName, fromTable.getName());
                }
            }
            
            // 构建DELETE语句
            Delete delete = new Delete();
            delete.setTable(new Table(tableName));
            
            // 复制WHERE条件
            Expression whereClause = plainSelect.getWhere();
            if (whereClause != null) {
                delete.setWhere(whereClause);
            } else {
                log.warn("DELETE statement has no WHERE clause - this will delete all rows!");
            }
            
            String deleteSql = delete.toString();
            deleteSql = restoreMybatisParams(deleteSql, normalized.placeholderMap);
            log.debug("Rewritten DELETE SQL: {}", deleteSql);
            
            return deleteSql;
            
        } catch (JSQLParserException e) {
            log.error("Failed to parse SQL for DELETE rewrite: {}", selectSql, e);
            throw new MetaServiceException("SQL parse failed: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Failed to rewrite SQL for DELETE: {}", selectSql, e);
            throw new MetaServiceException("SQL rewrite failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * 验证SQL是否为SELECT语句
     * 
     * @param sql SQL语句
     * @return true如果是SELECT语句
     */
    public boolean isSelectStatement(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return false;
        }
        
        try {
            NormalizedSql normalized = normalizeMybatisParams(sql);
            Statement stmt = CCJSqlParserUtil.parse(normalized.sql);
            return stmt instanceof Select;
        } catch (Exception e) {
            log.debug("Failed to parse SQL: {}", sql, e);
            return false;
        }
    }
    
    /**
     * Extract WHERE clause from a SELECT statement.
     *
     * @param selectSql SELECT SQL
     * @return WHERE expression, empty when no WHERE clause exists
     */
    public Optional<Expression> extractWhereClause(String selectSql) {
        if (selectSql == null || selectSql.trim().isEmpty()) {
            throw new IllegalArgumentException("SQL cannot be null or empty");
        }

        try {
            NormalizedSql normalized = normalizeMybatisParams(selectSql);
            Statement stmt = CCJSqlParserUtil.parse(normalized.sql);

            if (!(stmt instanceof Select)) {
                throw new MetaServiceException("Unsupported statement type: " + stmt.getClass().getSimpleName());
            }

            Select select = (Select) stmt;
            if (select.getPlainSelect() == null) {
                throw new MetaServiceException("Unsupported SELECT format: only plain SELECT is supported");
            }

            PlainSelect plainSelect = select.getPlainSelect();
            return Optional.ofNullable(plainSelect.getWhere());
        } catch (JSQLParserException e) {
            throw new MetaServiceException("SQL parse failed: " + e.getMessage(), e);
        } catch (MetaServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new MetaServiceException("Failed to extract WHERE clause: " + e.getMessage(), e);
        }
    }

    private NormalizedSql normalizeMybatisParams(String sql) {
        Matcher matcher = MYBATIS_PARAM_PATTERN.matcher(sql);
        if (!matcher.find()) {
            return new NormalizedSql(sql, Collections.emptyMap());
        }

        matcher.reset();
        Map<String, String> placeholderMap = new LinkedHashMap<>();
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String paramName = matcher.group(1);
            String token = ":_p" + paramName;
            placeholderMap.put(token, matcher.group(0));
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(token));
        }
        matcher.appendTail(buffer);
        return new NormalizedSql(buffer.toString(), placeholderMap);
    }

    private String restoreMybatisParams(String sql, Map<String, String> placeholderMap) {
        if (placeholderMap.isEmpty()) {
            return sql;
        }
        String restored = sql;
        for (Map.Entry<String, String> entry : placeholderMap.entrySet()) {
            restored = restored.replace(entry.getKey(), entry.getValue());
        }
        return restored;
    }
}
