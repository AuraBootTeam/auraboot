package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.service.QueryBuilderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QueryBuilder IN/NOT_IN操作符单元测试
 * 
 * 测试目标:
 * 1. 验证IN查询List参数正确展开
 * 2. 验证NOT_IN查询List参数正确展开
 * 3. 验证空List边界情况处理
 * 4. 验证单值List处理
 * 5. 验证null值处理
 * 
 * @author AuraBoot Team
 * @since 2.1.0
 */
@DisplayName("QueryBuilder IN/NOT_IN操作符测试")
class QueryBuilderInOperatorTest {

    private QueryBuilderService queryBuilderService;
    private ModelDefinition testModel;

    @BeforeEach
    void setUp() {
        queryBuilderService = new QueryBuilderServiceImpl(null);
        
        // 准备测试模型
        testModel = ModelDefinition.builder()
            .id(1L)
            .code("test_model")
            .name("测试模型")
            .tableName("test_table")
            .fields(Arrays.asList(
                FieldDefinition.builder()
                    .code("id")
                    .columnName("id")
                    .dataType("long")
                    .build(),
                FieldDefinition.builder()
                    .code("status")
                    .columnName("status")
                    .dataType("string")
                    .build(),
                FieldDefinition.builder()
                    .code("type")
                    .columnName("type")
                    .dataType("string")
                    .build()
            ))
            .build();
    }

    @Nested
    @DisplayName("IN操作符测试")
    class InOperatorTests {

        @Test
        @DisplayName("需求4.1验证: 多值List正确展开为多个占位符")
        void testInOperatorWithMultipleValues() {
            // Given: 创建包含多个值的IN条件
            List<String> statusValues = Arrays.asList("active", "pending", "approved");
            QueryCondition condition = QueryCondition.builder()
                .fieldName("status")
                .operator(QueryCondition.Operator.IN)
                .value(statusValues)
                .build();

            // When: 构建查询
            QueryBuilderService.QueryBuilder builder = queryBuilderService
                .buildConditionQuery(testModel, Collections.singletonList(condition));

            // Then: 验证SQL包含3个占位符
            String sql = builder.getSql();
            System.out.println("Generated SQL: " + sql);
            
            assertTrue(sql.contains("IN ("), "SQL应该包含IN子句");
            
            // 验证参数数量
            List<Object> parameters = builder.getParameters();
            assertEquals(3, parameters.size(), "应该有3个参数");
            assertEquals("active", parameters.get(0));
            assertEquals("pending", parameters.get(1));
            assertEquals("approved", parameters.get(2));
            
            // 验证占位符数量
            long placeholderCount = sql.chars().filter(ch -> ch == '#').count();
            assertEquals(3, placeholderCount, "应该有3个占位符");
        }

        @Test
        @DisplayName("需求4.3验证: 空List生成合法SQL")
        void testInOperatorWithEmptyList() {
            // Given: 创建空List的IN条件
            QueryCondition condition = QueryCondition.builder()
                .fieldName("status")
                .operator(QueryCondition.Operator.IN)
                .value(Collections.emptyList())
                .build();

            // When: 构建查询
            QueryBuilderService.QueryBuilder builder = queryBuilderService
                .buildConditionQuery(testModel, Collections.singletonList(condition));

            // Then: 验证生成恒假条件
            String sql = builder.getSql();
            System.out.println("Generated SQL for empty list: " + sql);
            
            assertTrue(sql.contains("1=0"), "空List的IN应该生成恒假条件 1=0");
            
            // 验证没有参数
            List<Object> parameters = builder.getParameters();
            assertEquals(0, parameters.size(), "空List不应该有参数");
        }

        @Test
        @DisplayName("需求4.4验证: 单值List正确处理")
        void testInOperatorWithSingleValue() {
            // Given: 创建单值List的IN条件
            QueryCondition condition = QueryCondition.builder()
                .fieldName("status")
                .operator(QueryCondition.Operator.IN)
                .value(Collections.singletonList("active"))
                .build();

            // When: 构建查询
            QueryBuilderService.QueryBuilder builder = queryBuilderService
                .buildConditionQuery(testModel, Collections.singletonList(condition));

            // Then: 验证SQL包含1个占位符
            String sql = builder.getSql();
            System.out.println("Generated SQL for single value: " + sql);
            
            assertTrue(sql.contains("IN ("), "SQL应该包含IN子句");
            
            // 验证参数数量
            List<Object> parameters = builder.getParameters();
            assertEquals(1, parameters.size(), "应该有1个参数");
            assertEquals("active", parameters.get(0));
        }

        @Test
        @DisplayName("需求4.3验证: null值生成恒假条件")
        void testInOperatorWithNullValue() {
            // Given: 创建null值的IN条件
            QueryCondition condition = QueryCondition.builder()
                .fieldName("status")
                .operator(QueryCondition.Operator.IN)
                .value(null)
                .build();

            // When: 构建查询
            QueryBuilderService.QueryBuilder builder = queryBuilderService
                .buildConditionQuery(testModel, Collections.singletonList(condition));

            // Then: 验证生成恒假条件
            String sql = builder.getSql();
            System.out.println("Generated SQL for null: " + sql);
            
            assertTrue(sql.contains("1=0"), "null值的IN应该生成恒假条件 1=0");
            
            // 验证没有参数
            List<Object> parameters = builder.getParameters();
            assertEquals(0, parameters.size(), "null值不应该有参数");
        }

        @Test
        @DisplayName("需求4.4验证: 数字类型List正确处理")
        void testInOperatorWithNumericValues() {
            // Given: 创建数字类型的IN条件
            List<Long> idValues = Arrays.asList(1L, 2L, 3L, 4L, 5L);
            QueryCondition condition = QueryCondition.builder()
                .fieldName("id")
                .operator(QueryCondition.Operator.IN)
                .value(idValues)
                .build();

            // When: 构建查询
            QueryBuilderService.QueryBuilder builder = queryBuilderService
                .buildConditionQuery(testModel, Collections.singletonList(condition));

            // Then: 验证SQL和参数
            String sql = builder.getSql();
            System.out.println("Generated SQL for numeric values: " + sql);
            
            assertTrue(sql.contains("IN ("), "SQL应该包含IN子句");
            
            // 验证参数数量
            List<Object> parameters = builder.getParameters();
            assertEquals(5, parameters.size(), "应该有5个参数");
            
            // 验证参数值
            for (int i = 0; i < 5; i++) {
                assertEquals(Long.valueOf(i + 1), parameters.get(i));
            }
        }
    }

    @Nested
    @DisplayName("NOT_IN操作符测试")
    class NotInOperatorTests {

        @Test
        @DisplayName("需求4.2验证: NOT_IN多值List正确展开")
        void testNotInOperatorWithMultipleValues() {
            // Given: 创建包含多个值的NOT_IN条件
            List<String> statusValues = Arrays.asList("deleted", "archived");
            QueryCondition condition = QueryCondition.builder()
                .fieldName("status")
                .operator(QueryCondition.Operator.NOT_IN)
                .value(statusValues)
                .build();

            // When: 构建查询
            QueryBuilderService.QueryBuilder builder = queryBuilderService
                .buildConditionQuery(testModel, Collections.singletonList(condition));

            // Then: 验证SQL包含2个占位符
            String sql = builder.getSql();
            System.out.println("Generated SQL for NOT_IN: " + sql);
            
            assertTrue(sql.contains("NOT IN ("), "SQL应该包含NOT IN子句");
            
            // 验证参数数量
            List<Object> parameters = builder.getParameters();
            assertEquals(2, parameters.size(), "应该有2个参数");
            assertEquals("deleted", parameters.get(0));
            assertEquals("archived", parameters.get(1));
        }

        @Test
        @DisplayName("需求4.3验证: NOT_IN空List生成恒真条件")
        void testNotInOperatorWithEmptyList() {
            // Given: 创建空List的NOT_IN条件
            QueryCondition condition = QueryCondition.builder()
                .fieldName("status")
                .operator(QueryCondition.Operator.NOT_IN)
                .value(Collections.emptyList())
                .build();

            // When: 构建查询
            QueryBuilderService.QueryBuilder builder = queryBuilderService
                .buildConditionQuery(testModel, Collections.singletonList(condition));

            // Then: 验证生成恒真条件
            String sql = builder.getSql();
            System.out.println("Generated SQL for NOT_IN empty list: " + sql);
            
            assertTrue(sql.contains("1=1"), "空List的NOT_IN应该生成恒真条件 1=1");
            
            // 验证没有参数
            List<Object> parameters = builder.getParameters();
            assertEquals(0, parameters.size(), "空List不应该有参数");
        }

        @Test
        @DisplayName("需求4.3验证: NOT_IN null值生成恒真条件")
        void testNotInOperatorWithNullValue() {
            // Given: 创建null值的NOT_IN条件
            QueryCondition condition = QueryCondition.builder()
                .fieldName("status")
                .operator(QueryCondition.Operator.NOT_IN)
                .value(null)
                .build();

            // When: 构建查询
            QueryBuilderService.QueryBuilder builder = queryBuilderService
                .buildConditionQuery(testModel, Collections.singletonList(condition));

            // Then: 验证生成恒真条件
            String sql = builder.getSql();
            System.out.println("Generated SQL for NOT_IN null: " + sql);
            
            assertTrue(sql.contains("1=1"), "null值的NOT_IN应该生成恒真条件 1=1");
            
            // 验证没有参数
            List<Object> parameters = builder.getParameters();
            assertEquals(0, parameters.size(), "null值不应该有参数");
        }
    }

    @Nested
    @DisplayName("边界情况测试")
    class EdgeCaseTests {

        @Test
        @DisplayName("测试大量值的IN查询")
        void testInOperatorWithManyValues() {
            // Given: 创建包含100个值的IN条件
            List<Long> manyIds = new ArrayList<>();
            for (long i = 1; i <= 100; i++) {
                manyIds.add(i);
            }
            
            QueryCondition condition = QueryCondition.builder()
                .fieldName("id")
                .operator(QueryCondition.Operator.IN)
                .value(manyIds)
                .build();

            // When: 构建查询
            QueryBuilderService.QueryBuilder builder = queryBuilderService
                .buildConditionQuery(testModel, Collections.singletonList(condition));

            // Then: 验证参数数量
            List<Object> parameters = builder.getParameters();
            assertEquals(100, parameters.size(), "应该有100个参数");
            
            // 验证SQL生成成功
            String sql = builder.getSql();
            assertNotNull(sql);
            assertTrue(sql.contains("IN ("), "SQL应该包含IN子句");
        }

        @Test
        @DisplayName("测试混合类型的IN查询")
        void testInOperatorWithMixedTypes() {
            // Given: 创建包含不同类型值的IN条件
            List<Object> mixedValues = Arrays.asList("active", "pending", "approved");
            QueryCondition condition = QueryCondition.builder()
                .fieldName("status")
                .operator(QueryCondition.Operator.IN)
                .value(mixedValues)
                .build();

            // When: 构建查询
            QueryBuilderService.QueryBuilder builder = queryBuilderService
                .buildConditionQuery(testModel, Collections.singletonList(condition));

            // Then: 验证参数正确处理
            List<Object> parameters = builder.getParameters();
            assertEquals(3, parameters.size(), "应该有3个参数");
        }

        @Test
        @DisplayName("测试Collection类型的IN查询")
        void testInOperatorWithCollection() {
            // Given: 创建Set类型的IN条件
            Set<String> statusSet = new HashSet<>(Arrays.asList("active", "pending"));
            QueryCondition condition = QueryCondition.builder()
                .fieldName("status")
                .operator(QueryCondition.Operator.IN)
                .value(statusSet)
                .build();

            // When: 构建查询
            QueryBuilderService.QueryBuilder builder = queryBuilderService
                .buildConditionQuery(testModel, Collections.singletonList(condition));

            // Then: 验证参数数量
            List<Object> parameters = builder.getParameters();
            assertEquals(2, parameters.size(), "应该有2个参数");
            
            // 验证SQL生成成功
            String sql = builder.getSql();
            assertTrue(sql.contains("IN ("), "SQL应该包含IN子句");
        }

        @Test
        @DisplayName("测试单个非List值的IN查询")
        void testInOperatorWithSingleNonListValue() {
            // Given: 创建单个非List值的IN条件
            QueryCondition condition = QueryCondition.builder()
                .fieldName("status")
                .operator(QueryCondition.Operator.IN)
                .value("active")
                .build();

            // When: 构建查询
            QueryBuilderService.QueryBuilder builder = queryBuilderService
                .buildConditionQuery(testModel, Collections.singletonList(condition));

            // Then: 验证自动转换为List
            List<Object> parameters = builder.getParameters();
            assertEquals(1, parameters.size(), "应该有1个参数");
            assertEquals("active", parameters.get(0));
            
            // 验证SQL生成成功
            String sql = builder.getSql();
            assertTrue(sql.contains("IN ("), "SQL应该包含IN子句");
        }
    }

    @Nested
    @DisplayName("SQL格式验证测试")
    class SqlFormatTests {

        @Test
        @DisplayName("验证IN查询SQL格式正确")
        void testInQuerySqlFormat() {
            // Given
            List<String> values = Arrays.asList("A", "B", "C");
            QueryCondition condition = QueryCondition.builder()
                .fieldName("type")
                .operator(QueryCondition.Operator.IN)
                .value(values)
                .build();

            // When
            QueryBuilderService.QueryBuilder builder = queryBuilderService
                .buildConditionQuery(testModel, Collections.singletonList(condition));
            String sql = builder.getSql();

            // Then: 验证SQL格式
            System.out.println("SQL Format: " + sql);
            
            // 应该包含SELECT FROM WHERE
            assertTrue(sql.contains("select"));
            assertTrue(sql.contains("from test_table"));
            assertTrue(sql.contains("where"));
            assertTrue(sql.contains("type IN ("));
            
            // 应该包含正确数量的占位符
            long placeholderCount = sql.chars().filter(ch -> ch == '#').count();
            assertEquals(3, placeholderCount);
        }

        @Test
        @DisplayName("验证多个条件组合的SQL格式")
        void testMultipleConditionsSqlFormat() {
            // Given: 创建多个条件
            List<QueryCondition> conditions = Arrays.asList(
                QueryCondition.builder()
                    .fieldName("status")
                    .operator(QueryCondition.Operator.IN)
                    .value(Arrays.asList("active", "pending"))
                    .build(),
                QueryCondition.builder()
                    .fieldName("type")
                    .operator(QueryCondition.Operator.EQ)
                    .value("user")
                    .build()
            );

            // When
            QueryBuilderService.QueryBuilder builder = queryBuilderService
                .buildConditionQuery(testModel, conditions);
            String sql = builder.getSql();

            // Then: 验证SQL包含AND连接
            System.out.println("Multiple conditions SQL: " + sql);
            
            assertTrue(sql.contains("and"), "多个条件应该用AND连接");
            assertTrue(sql.contains("status IN ("));
            assertTrue(sql.contains("type ="));
            
            // 验证参数数量: 2个IN参数 + 1个EQ参数
            List<Object> parameters = builder.getParameters();
            assertEquals(3, parameters.size());
        }
    }
}
