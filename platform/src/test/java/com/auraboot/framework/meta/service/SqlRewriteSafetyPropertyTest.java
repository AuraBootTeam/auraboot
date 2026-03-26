package com.auraboot.framework.meta.service;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.meta.exception.MetaServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SQL重写安全性属性测试
 * 
 * Feature: git-first-architecture-alignment
 * Property 5: SQL重写安全性保证
 * 
 * 属性：对于任何有效的SELECT语句，SecureSqlRewriter应该能够安全地重写为COUNT或DELETE语句，
 * 处理多行SQL、子查询、复杂WHERE条件和JOIN语句，不会产生SQL注入漏洞
 * 
 * 验证：需求4.1, 4.2, 4.3, 4.4, 4.5
 * 
 * @author AuraBoot Team
 * @since 2.1.0
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
@DisplayName("Property 5: SQL Rewrite Safety")
public class SqlRewriteSafetyPropertyTest {

    @Autowired
    private SecureSqlRewriter secureSqlRewriter;

    private static final int PROPERTY_TEST_ITERATIONS = 100;

    @Test
    @DisplayName("Property 5.1: COUNT重写保持WHERE条件")
    void testCountRewritePreservesWhereClause() {
        System.out.println("\n=== Property 5.1: COUNT重写保持WHERE条件 ===");
        
        int successCount = 0;
        
        for (int i = 0; i < PROPERTY_TEST_ITERATIONS; i++) {
            // 生成随机SELECT语句
            String tableName = "test_table_" + i;
            String whereCondition = generateRandomWhereCondition(i);
            String originalSql = String.format("SELECT * FROM %s WHERE %s", tableName, whereCondition);
            
            // 重写为COUNT查询
            String countSql = secureSqlRewriter.rewriteForCount(originalSql);
            
            // 验证：COUNT查询应该包含原始WHERE条件
            assertNotNull(countSql, "COUNT SQL should not be null");
            assertTrue(countSql.toUpperCase().contains("COUNT"), 
                      "Rewritten SQL should contain COUNT");
            assertTrue(countSql.toUpperCase().contains("WHERE"), 
                      "Rewritten SQL should preserve WHERE clause");
            assertTrue(countSql.contains(whereCondition), 
                      "Rewritten SQL should preserve original WHERE condition");
            
            successCount++;
        }
        
        System.out.println("✓ 完成 " + successCount + " 次迭代");
        System.out.println("✓ COUNT重写保持WHERE条件");
    }

    @Test
    @DisplayName("Property 5.2: COUNT重写移除ORDER BY")
    void testCountRewriteRemovesOrderBy() {
        System.out.println("\n=== Property 5.2: COUNT重写移除ORDER BY ===");
        
        int successCount = 0;
        
        for (int i = 0; i < PROPERTY_TEST_ITERATIONS; i++) {
            // 生成包含ORDER BY的SELECT语句
            String tableName = "test_table_" + i;
            String orderByClause = generateRandomOrderBy(i);
            String originalSql = String.format("SELECT * FROM %s WHERE id > 0 ORDER BY %s", 
                                             tableName, orderByClause);
            
            // 重写为COUNT查询
            String countSql = secureSqlRewriter.rewriteForCount(originalSql);
            
            // 验证：COUNT查询不应该包含ORDER BY
            assertNotNull(countSql);
            assertTrue(countSql.toUpperCase().contains("COUNT"));
            assertFalse(countSql.toUpperCase().contains("ORDER BY"), 
                       "COUNT query should not contain ORDER BY clause");
            
            successCount++;
        }
        
        System.out.println("✓ 完成 " + successCount + " 次迭代");
        System.out.println("✓ COUNT重写移除ORDER BY");
    }

    @Test
    @DisplayName("Property 5.3: COUNT重写移除LIMIT和OFFSET")
    void testCountRewriteRemovesLimitAndOffset() {
        System.out.println("\n=== Property 5.3: COUNT重写移除LIMIT和OFFSET ===");
        
        int successCount = 0;
        
        for (int i = 0; i < PROPERTY_TEST_ITERATIONS; i++) {
            // 生成包含LIMIT和OFFSET的SELECT语句
            String tableName = "test_table_" + i;
            int limit = 10 + (i % 90);
            int offset = i % 100;
            String originalSql = String.format("SELECT * FROM %s WHERE id > 0 LIMIT %d OFFSET %d", 
                                             tableName, limit, offset);
            
            // 重写为COUNT查询
            String countSql = secureSqlRewriter.rewriteForCount(originalSql);
            
            // 验证：COUNT查询不应该包含LIMIT和OFFSET
            assertNotNull(countSql);
            assertTrue(countSql.toUpperCase().contains("COUNT"));
            assertFalse(countSql.toUpperCase().contains("LIMIT"), 
                       "COUNT query should not contain LIMIT clause");
            assertFalse(countSql.toUpperCase().contains("OFFSET"), 
                       "COUNT query should not contain OFFSET clause");
            
            successCount++;
        }
        
        System.out.println("✓ 完成 " + successCount + " 次迭代");
        System.out.println("✓ COUNT重写移除LIMIT和OFFSET");
    }

    @Test
    @DisplayName("Property 5.4: DELETE重写保持WHERE条件")
    void testDeleteRewritePreservesWhereClause() {
        System.out.println("\n=== Property 5.4: DELETE重写保持WHERE条件 ===");
        
        int successCount = 0;
        
        for (int i = 0; i < PROPERTY_TEST_ITERATIONS; i++) {
            // 生成随机SELECT语句
            String tableName = "test_table_" + i;
            String whereCondition = generateRandomWhereCondition(i);
            String originalSql = String.format("SELECT * FROM %s WHERE %s", tableName, whereCondition);
            
            // 重写为DELETE语句
            String deleteSql = secureSqlRewriter.rewriteForDelete(originalSql, tableName);
            
            // 验证：DELETE语句应该包含原始WHERE条件
            assertNotNull(deleteSql, "DELETE SQL should not be null");
            assertTrue(deleteSql.toUpperCase().startsWith("DELETE"), 
                      "Rewritten SQL should be a DELETE statement");
            assertTrue(deleteSql.toUpperCase().contains("WHERE"), 
                      "DELETE SQL should preserve WHERE clause");
            assertTrue(deleteSql.contains(whereCondition), 
                      "DELETE SQL should preserve original WHERE condition");
            
            successCount++;
        }
        
        System.out.println("✓ 完成 " + successCount + " 次迭代");
        System.out.println("✓ DELETE重写保持WHERE条件");
    }

    @Test
    @DisplayName("Property 5.5: 多行SQL正确处理")
    void testMultilineSqlHandling() {
        System.out.println("\n=== Property 5.5: 多行SQL正确处理 ===");
        
        int successCount = 0;
        
        for (int i = 0; i < PROPERTY_TEST_ITERATIONS; i++) {
            // 生成多行SELECT语句
            String tableName = "test_table_" + i;
            String multilineSql = String.format(
                "SELECT\n" +
                "  id,\n" +
                "  name,\n" +
                "  status\n" +
                "FROM %s\n" +
                "WHERE\n" +
                "  status = 'active'\n" +
                "  AND id > %d",
                tableName, i
            );
            
            // 重写为COUNT查询
            String countSql = secureSqlRewriter.rewriteForCount(multilineSql);
            
            // 验证：应该成功重写
            assertNotNull(countSql);
            assertTrue(countSql.toUpperCase().contains("COUNT"));
            assertTrue(countSql.toUpperCase().contains("WHERE"));
            assertTrue(countSql.contains("active"));
            
            successCount++;
        }
        
        System.out.println("✓ 完成 " + successCount + " 次迭代");
        System.out.println("✓ 多行SQL正确处理");
    }

    @Test
    @DisplayName("Property 5.6: 保持MyBatis参数占位符")
    void testMyBatisParamsPreserved() {
        String originalSql = "SELECT id, name FROM mt WHERE tenant_id = #{params.param0} " +
                "AND status = #{params.param1} ORDER BY id LIMIT 10";

        String countSql = secureSqlRewriter.rewriteForCount(originalSql);

        assertNotNull(countSql, "COUNT SQL should not be null");
        assertTrue(countSql.contains("#{params.param0}"), "COUNT SQL should preserve param0 placeholder");
        assertTrue(countSql.contains("#{params.param1}"), "COUNT SQL should preserve param1 placeholder");
        assertFalse(countSql.toUpperCase().contains("ORDER BY"), "COUNT query should not contain ORDER BY");
        assertFalse(countSql.toUpperCase().contains("LIMIT"), "COUNT query should not contain LIMIT");
    }

    @Test
    @DisplayName("Property 5.6: 复杂WHERE条件正确处理")
    void testComplexWhereConditions() {
        System.out.println("\n=== Property 5.6: 复杂WHERE条件正确处理 ===");
        
        int successCount = 0;
        
        for (int i = 0; i < PROPERTY_TEST_ITERATIONS; i++) {
            // 生成包含复杂WHERE条件的SELECT语句
            String tableName = "test_table_" + i;
            String complexWhere = String.format(
                "(status = 'active' OR status = 'pending') AND " +
                "(created_at > '2024-01-01' AND created_at < '2024-12-31') AND " +
                "id IN (%d, %d, %d)",
                i, i+1, i+2
            );
            String originalSql = String.format("SELECT * FROM %s WHERE %s", tableName, complexWhere);
            
            // 重写为COUNT查询
            String countSql = secureSqlRewriter.rewriteForCount(originalSql);
            
            // 验证：复杂WHERE条件应该被保留
            assertNotNull(countSql);
            assertTrue(countSql.toUpperCase().contains("COUNT"));
            assertTrue(countSql.contains("active"));
            assertTrue(countSql.contains("pending"));
            assertTrue(countSql.contains("created_at"));
            
            successCount++;
        }
        
        System.out.println("✓ 完成 " + successCount + " 次迭代");
        System.out.println("✓ 复杂WHERE条件正确处理");
    }

    @Test
    @DisplayName("Property 5.7: JOIN语句正确处理")
    void testJoinStatements() {
        System.out.println("\n=== Property 5.7: JOIN语句正确处理 ===");
        
        int successCount = 0;
        
        for (int i = 0; i < PROPERTY_TEST_ITERATIONS; i++) {
            // 生成包含JOIN的SELECT语句
            String table1 = "users_" + i;
            String table2 = "orders_" + i;
            String joinSql = String.format(
                "SELECT u.*, o.order_id FROM %s u " +
                "INNER JOIN %s o ON u.id = o.user_id " +
                "WHERE u.status = 'active'",
                table1, table2
            );
            
            // 重写为COUNT查询
            String countSql = secureSqlRewriter.rewriteForCount(joinSql);
            
            // 验证：JOIN应该被保留
            assertNotNull(countSql);
            assertTrue(countSql.toUpperCase().contains("COUNT"));
            assertTrue(countSql.toUpperCase().contains("JOIN") || 
                      countSql.toUpperCase().contains("INNER JOIN"));
            
            successCount++;
        }
        
        System.out.println("✓ 完成 " + successCount + " 次迭代");
        System.out.println("✓ JOIN语句正确处理");
    }

    @Test
    @DisplayName("Property 5.8: 无效SQL抛出异常")
    void testInvalidSqlThrowsException() {
        System.out.println("\n=== Property 5.8: 无效SQL抛出异常 ===");
        
        int successCount = 0;
        
        for (int i = 0; i < PROPERTY_TEST_ITERATIONS; i++) {
            // 生成无效SQL
            String[] invalidSqls = {
                "INVALID SQL STATEMENT",
                "SELECT * FROM",  // 不完整
                "DELETE FROM table",  // 不是SELECT
                "UPDATE table SET x=1",  // 不是SELECT
                "",  // 空字符串
                null  // null
            };
            
            String invalidSql = invalidSqls[i % invalidSqls.length];
            
            if (invalidSql == null || invalidSql.trim().isEmpty()) {
                // 空或null应该抛出IllegalArgumentException
                assertThrows(IllegalArgumentException.class, 
                           () -> secureSqlRewriter.rewriteForCount(invalidSql),
                           "Should throw IllegalArgumentException for null/empty SQL");
            } else if (!invalidSql.toUpperCase().trim().startsWith("select")) {
                // 非SELECT语句应该抛出异常
                assertThrows(Exception.class, 
                           () -> secureSqlRewriter.rewriteForCount(invalidSql),
                           "Should throw exception for non-SELECT SQL");
            } else {
                // 不完整的SELECT应该抛出解析异常
                assertThrows(MetaServiceException.class, 
                           () -> secureSqlRewriter.rewriteForCount(invalidSql),
                           "Should throw MetaServiceException for invalid SQL");
            }
            
            successCount++;
        }
        
        System.out.println("✓ 完成 " + successCount + " 次迭代");
        System.out.println("✓ 无效SQL抛出异常");
    }

    // 辅助方法：生成随机WHERE条件
    private String generateRandomWhereCondition(int seed) {
        Random random = new Random(seed);
        String[] conditions = {
            "id > " + random.nextInt(1000),
            "status = 'active'",
            "created_at > '2024-01-01'",
            "name LIKE '%test%'",
            "tenant_id = " + (1 + random.nextInt(10))
        };
        
        return conditions[random.nextInt(conditions.length)];
    }

    // 辅助方法：生成随机ORDER BY子句
    private String generateRandomOrderBy(int seed) {
        Random random = new Random(seed);
        String[] orderBys = {
            "id DESC",
            "created_at ASC",
            "name",
            "status, id DESC"
        };
        
        return orderBys[random.nextInt(orderBys.length)];
    }
}
