package com.auraboot.framework.meta.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 动态数据访问映射器
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Mapper
public interface DynamicDataMapper {

    /**
     * 根据SQL查询数据
     * @param sql SQL语句
     * @param params 参数映射
     * @return 查询结果
     */
    @SelectProvider(type = DynamicSqlProvider.class, method = "selectByQuery")
    List<Map<String, Object>> selectByQuery(@Param("sql") String sql, @Param("params") Map<String, Object> params);

    /**
     * 根据SQL统计数量
     * @param sql SQL语句
     * @param params 参数映射
     * @return 记录数量
     */
    @SelectProvider(type = DynamicSqlProvider.class, method = "countByQuery")
    Long countByQuery(@Param("sql") String sql, @Param("params") Map<String, Object> params);

    /**
     * 插入数据
     * @param tableName 表名
     * @param data 数据
     * @return 影响行数
     */
    @InsertProvider(type = DynamicSqlProvider.class, method = "insert")
    int insert(@Param("tableName") String tableName, @Param("data") Map<String, Object> data);

    /**
     * 更新数据
     * @param tableName 表名
     * @param data 更新数据
     * @param conditions 更新条件
     * @return 影响行数
     */
    @UpdateProvider(type = DynamicSqlProvider.class, method = "update")
    int update(@Param("tableName") String tableName,
               @Param("data") Map<String, Object> data,
               @Param("conditions") Map<String, Object> conditions);

    /**
     * Insert with JSONB column awareness — adds ::jsonb cast for specified columns.
     */
    @InsertProvider(type = DynamicSqlProvider.class, method = "insert")
    int insertWithJsonb(@Param("tableName") String tableName, @Param("data") Map<String, Object> data,
                        @Param("jsonbColumns") Set<String> jsonbColumns);

    /**
     * Update with JSONB column awareness — adds ::jsonb cast for specified columns.
     */
    @UpdateProvider(type = DynamicSqlProvider.class, method = "update")
    int updateWithJsonb(@Param("tableName") String tableName,
                        @Param("data") Map<String, Object> data,
                        @Param("conditions") Map<String, Object> conditions,
                        @Param("jsonbColumns") Set<String> jsonbColumns);

    /**
     * 删除数据
     * @param tableName 表名
     * @param conditions 删除条件
     * @return 影响行数
     */
    @DeleteProvider(type = DynamicSqlProvider.class, method = "delete")
    int delete(@Param("tableName") String tableName, @Param("conditions") Map<String, Object> conditions);

    /**
     * 根据SQL删除数据
     * @param sql SQL语句
     * @param params 参数映射
     * @return 影响行数
     */
    @DeleteProvider(type = DynamicSqlProvider.class, method = "deleteByQuery")
    int deleteByQuery(@Param("sql") String sql, @Param("params") Map<String, Object> params);

    /**
     * 批量插入数据
     * @param tableName 表名
     * @param dataList 数据列表
     * @return 影响行数
     */
    @InsertProvider(type = DynamicSqlProvider.class, method = "batchInsert")
    int batchInsert(@Param("tableName") String tableName, @Param("dataList") List<Map<String, Object>> dataList);

    /**
     * Query data bypassing tenant interceptor.
     * Use ONLY when tenant isolation is already handled inside the SQL (e.g., NamedQuery fromSql with #{params.tenantId}).
     * This avoids JSqlParser failures on complex PostgreSQL-specific syntax (DATE_TRUNC, ::date, window functions)
     * and prevents double-filtering when TenantLineInterceptor can't properly handle deeply nested subqueries.
     *
     * <p><b>SECURITY: Tenant bypass — callers MUST ensure tenant isolation.</b></p>
     * <p>Authorized callers (audited 2026-03-09):</p>
     * <ul>
     *   <li>ReportTemplateServiceImpl — CUSTOM_SQL datasource; validated by SqlSafetyUtils + mandatory #{params.tenantId}</li>
     *   <li>AggregateQueryServiceImpl — NamedQuery aggregate; tenantId injected as parameter</li>
     *   <li>NamedQueryServiceImpl — NQ execution; tenantId injected at line 599, fromSql contains #{params.tenantId}</li>
     * </ul>
     * <p>Adding new callers requires security review.</p>
     */
    @SelectProvider(type = DynamicSqlProvider.class, method = "selectByQuery")
    @InterceptorIgnore(tenantLine = "true")
    List<Map<String, Object>> selectByQueryWithoutTenant(@Param("sql") String sql, @Param("params") Map<String, Object> params);

    /**
     * Count query bypassing tenant interceptor.
     * Same security model as selectByQueryWithoutTenant — tenant isolation must be in the SQL.
     */
    @SelectProvider(type = DynamicSqlProvider.class, method = "countByQuery")
    @InterceptorIgnore(tenantLine = "true")
    Long countByQueryWithoutTenant(@Param("sql") String sql, @Param("params") Map<String, Object> params);

    /**
     * 执行自定义SQL
     * @param sql SQL语句
     * @param params 参数映射
     * @return 执行结果
     */
    @SelectProvider(type = DynamicSqlProvider.class, method = "executeCustomSql")
    List<Map<String, Object>> executeCustomSql(@Param("sql") String sql, @Param("params") Map<String, Object> params);

    /**
     * 查询数据列表（兼容方法）
     * @param tableName 表名
     * @param columns 查询列
     * @param whereClause WHERE条件
     * @param orderBy 排序
     * @param limit 限制数量
     * @param offset 偏移量
     * @return 查询结果
     */
    @SelectProvider(type = DynamicSqlProvider.class, method = "queryList")
    List<Map<String, Object>> queryList(
            @Param("tableName") String tableName,
            @Param("columns") List<String> columns,
            @Param("whereClause") String whereClause,
            @Param("orderBy") String orderBy,
            @Param("limit") Integer limit,
            @Param("offset") Integer offset);

    /**
     * 根据条件字符串更新数据（兼容方法）
     * @param tableName 表名
     * @param data 更新数据
     * @param whereClause WHERE条件字符串
     * @return 影响行数
     */
    @UpdateProvider(type = DynamicSqlProvider.class, method = "updateByCondition")
    int updateByCondition(
            @Param("tableName") String tableName,
            @Param("data") Map<String, Object> data,
            @Param("whereClause") String whereClause);

    // ==================== 表结构管理 ====================

    /**
     * 创建表
     * @param createTableSql 建表SQL
     * @return 执行结果
     */
    @UpdateProvider(type = DynamicSqlProvider.class, method = "createTable")
    int createTable(@Param("createTableSql") String createTableSql);

    /**
     * 检查表是否存在（绕过租户拦截器）
     * @param tableName 表名
     * @return 表是否存在
     */
    @Select("""
        SELECT COUNT(*)
        FROM information_schema.tables
        WHERE table_schema = 'public'
        AND table_name = #{tableName}
        """)
    @InterceptorIgnore(tenantLine = "true")
    int checkTableExistsWithoutTenant(@Param("tableName") String tableName);

    /**
     * Check if a column exists in a table (bypasses tenant interceptor).
     * @param tableName table name
     * @param columnName column name
     * @return 1 if exists, 0 otherwise
     */
    @Select("""
        SELECT COUNT(*)
        FROM information_schema.columns
        WHERE table_schema = 'public'
        AND table_name = #{tableName}
        AND column_name = #{columnName}
        """)
    @InterceptorIgnore(tenantLine = "true")
    int checkColumnExists(@Param("tableName") String tableName, @Param("columnName") String columnName);

    /**
     * 添加列到表
     * @param alterTableSql 修改表SQL
     * @return 执行结果
     */
    @UpdateProvider(type = DynamicSqlProvider.class, method = "alterTable")
    int alterTable(@Param("alterTableSql") String alterTableSql);
}
