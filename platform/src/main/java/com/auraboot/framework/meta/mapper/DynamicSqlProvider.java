package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.security.SqlSafetyUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class DynamicSqlProvider {

    /**
     * Validate export SQL: must be a SELECT statement and pass safety checks.
     */
    public static void validateExportSql(String sql) {
        SqlSafetyUtils.validateSelectOnlySql(sql);
    }

    public static String selectByQuery(Map<String, Object> params) {
        String sql = requireSql(params);
        SqlSafetyUtils.validateSelectOnlySql(sql);
        return sql;
    }

    public static String countByQuery(Map<String, Object> params) {
        String sql = requireSql(params);
        SqlSafetyUtils.validateSelectOnlySql(sql);
        return sql;
    }

    public static String deleteByQuery(Map<String, Object> params) {
        String sql = requireSql(params);
        String normalized = sql.trim().toLowerCase();
        if (!normalized.startsWith("delete")) {
            throw new IllegalArgumentException("deleteByQuery SQL must be a DELETE statement");
        }
        if (normalized.contains(";") || normalized.contains("--") || normalized.contains("/*")) {
            throw new IllegalArgumentException("deleteByQuery SQL must not contain injection patterns");
        }
        return sql;
    }

    /**
     * @deprecated Dangerous: allows arbitrary SQL execution. Callers should use
     * typed methods (selectByQuery, insert, update, delete) instead.
     */
    @Deprecated
    public static String executeCustomSql(Map<String, Object> params) {
        return requireSql(params);
    }

    public static String insert(Map<String, Object> params) {
        String tableName = requireName((String) params.get("tableName"), "table name");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) params.get("data");
        @SuppressWarnings("unchecked")
        Set<String> jsonbColumns = params.containsKey("jsonbColumns")
                ? (Set<String>) params.get("jsonbColumns") : null;
        if (data == null || data.isEmpty()) {
            throw new IllegalArgumentException("Insert data cannot be empty");
        }

        StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO ").append(tableName).append(" (");
        boolean first = true;
        for (String key : data.keySet()) {
            requireName(key, "column name");
            if (!first) {
                sql.append(", ");
            }
            sql.append(key);
            first = false;
        }
        sql.append(") VALUES (");
        first = true;
        for (String key : data.keySet()) {
            if (!first) {
                sql.append(", ");
            }
            sql.append("#{data.").append(key).append("}");
            if (jsonbColumns != null && jsonbColumns.contains(key)) {
                sql.append("::jsonb");
            }
            first = false;
        }
        sql.append(")");
        return sql.toString();
    }

    public static String update(Map<String, Object> params) {
        String tableName = requireName((String) params.get("tableName"), "table name");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) params.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> conditions = (Map<String, Object>) params.get("conditions");
        @SuppressWarnings("unchecked")
        Set<String> jsonbColumns = params.containsKey("jsonbColumns")
                ? (Set<String>) params.get("jsonbColumns") : null;
        if (data == null || data.isEmpty()) {
            throw new IllegalArgumentException("Update data cannot be empty");
        }
        if (conditions == null || conditions.isEmpty()) {
            throw new IllegalArgumentException("Update conditions cannot be empty");
        }

        StringBuilder sql = new StringBuilder();
        sql.append("UPDATE ").append(tableName).append(" SET ");
        boolean first = true;
        for (String key : data.keySet()) {
            requireName(key, "column name");
            if (!first) {
                sql.append(", ");
            }
            sql.append(key).append(" = #{data.").append(key).append("}");
            // Add ::jsonb cast for JSONB columns
            if (jsonbColumns != null && jsonbColumns.contains(key)) {
                sql.append("::jsonb");
            }
            first = false;
        }
        sql.append(" WHERE ");
        first = true;
        for (String key : conditions.keySet()) {
            requireName(key, "condition column name");
            if (!first) {
                sql.append(" AND ");
            }
            sql.append(key).append(" = #{conditions.").append(key).append("}");
            first = false;
        }
        return sql.toString();
    }

    public static String delete(Map<String, Object> params) {
        String tableName = requireName((String) params.get("tableName"), "table name");
        @SuppressWarnings("unchecked")
        Map<String, Object> conditions = (Map<String, Object>) params.get("conditions");
        if (conditions == null || conditions.isEmpty()) {
            throw new IllegalArgumentException("Delete conditions cannot be empty");
        }

        StringBuilder sql = new StringBuilder();
        sql.append("DELETE FROM ").append(tableName).append(" WHERE ");
        boolean first = true;
        for (String key : conditions.keySet()) {
            requireName(key, "condition column name");
            if (!first) {
                sql.append(" AND ");
            }
            sql.append(key).append(" = #{conditions.").append(key).append("}");
            first = false;
        }
        return sql.toString();
    }

    public static String batchInsert(Map<String, Object> params) {
        String tableName = requireName((String) params.get("tableName"), "table name");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dataList = (List<Map<String, Object>>) params.get("dataList");
        if (dataList == null || dataList.isEmpty()) {
            throw new IllegalArgumentException("Batch insert data cannot be empty");
        }

        Map<String, Object> firstRow = dataList.get(0);
        if (firstRow.isEmpty()) {
            throw new IllegalArgumentException("Batch insert row cannot be empty");
        }

        StringBuilder sql = new StringBuilder();
        sql.append("INSERT INTO ").append(tableName).append(" (");
        boolean first = true;
        for (String key : firstRow.keySet()) {
            requireName(key, "column name");
            if (!first) {
                sql.append(", ");
            }
            sql.append(key);
            first = false;
        }
        sql.append(") VALUES ");

        for (int i = 0; i < dataList.size(); i++) {
            Map<String, Object> row = dataList.get(i);
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("(");
            first = true;
            for (String key : firstRow.keySet()) {
                if (!first) {
                    sql.append(", ");
                }
                sql.append("#{dataList[").append(i).append("].").append(key).append("}");
                first = false;
            }
            sql.append(")");
        }

        return sql.toString();
    }

    @SuppressWarnings("unchecked")
    public static String updateByCondition(Map<String, Object> params) {
        String tableName = requireName((String) params.get("tableName"), "table name");
        Map<String, Object> data = (Map<String, Object>) params.get("data");
        String whereClause = (String) params.get("whereClause");

        if (data == null || data.isEmpty()) {
            throw new IllegalArgumentException("Update data cannot be empty");
        }
        if (whereClause == null || whereClause.isBlank()) {
            throw new IllegalArgumentException("Where clause cannot be empty for update");
        }

        StringBuilder sql = new StringBuilder();
        sql.append("UPDATE ").append(tableName).append(" SET ");
        boolean first = true;
        for (String key : data.keySet()) {
            requireName(key, "column name");
            if (!first) {
                sql.append(", ");
            }
            sql.append(key).append(" = #{data.").append(key).append("}");
            first = false;
        }
        SqlSafetyUtils.validateSqlFragment(whereClause);
        sql.append(" WHERE ").append(whereClause);

        return sql.toString();
    }

    @SuppressWarnings("unchecked")
    public static String queryList(Map<String, Object> params) {
        String tableName = requireName((String) params.get("tableName"), "table name");
        List<String> columns = (List<String>) params.get("columns");
        String whereClause = (String) params.get("whereClause");
        String orderBy = (String) params.get("orderBy");
        Integer limit = (Integer) params.get("limit");
        Integer offset = (Integer) params.get("offset");

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ");

        // Build column list
        if (columns == null || columns.isEmpty() || (columns.size() == 1 && "*".equals(columns.get(0)))) {
            sql.append("*");
        } else {
            boolean first = true;
            for (String col : columns) {
                if (!"*".equals(col)) {
                    requireName(col, "column name");
                }
                if (!first) {
                    sql.append(", ");
                }
                sql.append(col);
                first = false;
            }
        }

        sql.append(" FROM ").append(tableName);

        // Add WHERE clause
        if (whereClause != null && !whereClause.isBlank()) {
            SqlSafetyUtils.validateSqlFragment(whereClause);
            sql.append(" WHERE ").append(whereClause);
        }

        // Add ORDER BY
        if (orderBy != null && !orderBy.isBlank()) {
            SqlSafetyUtils.validateSqlFragment(orderBy);
            sql.append(" ORDER BY ").append(orderBy);
        }

        // Add LIMIT
        if (limit != null && limit > 0) {
            sql.append(" LIMIT ").append(limit);
        }

        // Add OFFSET
        if (offset != null && offset > 0) {
            sql.append(" OFFSET ").append(offset);
        }

        return sql.toString();
    }

    public static String createTable(Map<String, Object> params) {
        return requireSqlByKey(params, "createTableSql");
    }

    public static String alterTable(Map<String, Object> params) {
        return requireSqlByKey(params, "alterTableSql");
    }

    private static String requireSql(Map<String, Object> params) {
        return requireSqlByKey(params, "sql");
    }

    private static String requireSqlByKey(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("SQL cannot be empty");
        }
        String sql = ((String) value).trim();
        if (sql.isEmpty()) {
            throw new IllegalArgumentException("SQL cannot be empty");
        }
        return sql;
    }

    private static String requireName(String name, String label) {
        SqlSafetyUtils.validateIdentifier(name, label);
        return name;
    }
}
