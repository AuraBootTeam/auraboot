package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.service.QueryBuilderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QueryBuilder IN/NOT_IN操作符属性测试
 * 
 * 使用Property-Based Testing验证IN/NOT_IN查询的通用正确性属性
 * 
 * @author AuraBoot Team
 * @since 2.1.0
 */
@DisplayName("QueryBuilder IN/NOT_IN操作符属性测试")
public class QueryBuilderInOperatorPropertyTest {

    /**
     * 属性8: IN查询List展开
     * 
     * 对于任意包含IN操作符的查询条件,当List参数包含N个值时,
     * 生成的SQL应该包含N个占位符
     * 
     * 验证: 需求 4.4
     */
    @Test
    @DisplayName("属性8: IN查询List展开 - 占位符数量应该等于List大小")
    void property8_InQueryListExpansion() {
        for (List<String> values : sampleStringLists()) {
            ModelDefinition model = createTestModel();
            QueryCondition condition = QueryCondition.builder()
                    .fieldName("status")
                    .operator(QueryCondition.Operator.IN)
                    .value(values)
                    .build();

            QueryBuilderService queryBuilderService = new QueryBuilderServiceImpl(null);
            QueryBuilderService.QueryBuilder builder = queryBuilderService
                    .buildConditionQuery(model, Collections.singletonList(condition));

            String sql = builder.getSql();
            List<Object> parameters = builder.getParameters();

            assertEquals(values.size(), parameters.size());
            assertTrue(sql.contains("IN ("));

            long placeholderCount = sql.chars().filter(ch -> ch == '#').count();
            assertEquals(values.size(), placeholderCount);

            for (int i = 0; i < values.size(); i++) {
                assertEquals(values.get(i), parameters.get(i));
            }
        }
    }

    /**
     * 属性8扩展: NOT_IN查询List展开
     * 
     * 对于任意包含NOT_IN操作符的查询条件,当List参数包含N个值时,
     * 生成的SQL应该包含N个占位符
     * 
     * 验证: 需求 4.4
     */
    @Test
    @DisplayName("属性8扩展: NOT_IN查询List展开 - 占位符数量应该等于List大小")
    void property8_NotInQueryListExpansion() {
        for (List<String> values : sampleStringLists()) {
            ModelDefinition model = createTestModel();
            QueryCondition condition = QueryCondition.builder()
                    .fieldName("status")
                    .operator(QueryCondition.Operator.NOT_IN)
                    .value(values)
                    .build();

            QueryBuilderService queryBuilderService = new QueryBuilderServiceImpl(null);
            QueryBuilderService.QueryBuilder builder = queryBuilderService
                    .buildConditionQuery(model, Collections.singletonList(condition));

            String sql = builder.getSql();
            List<Object> parameters = builder.getParameters();

            assertEquals(values.size(), parameters.size());
            assertTrue(sql.contains("NOT IN ("));

            long placeholderCount = sql.chars().filter(ch -> ch == '#').count();
            assertEquals(values.size(), placeholderCount);
        }
    }

    /**
     * 属性9: IN查询空List处理
     * 
     * 对于任意IN查询,当List为空时,应该生成恒假条件 (1=0)
     * 
     * 验证: 需求 4.3
     */
    @Test
    @DisplayName("属性9: IN查询空List处理 - 应该生成恒假条件")
    void property9_InQueryEmptyListHandling() {
        ModelDefinition model = createTestModel();
        QueryCondition condition = QueryCondition.builder()
                .fieldName("status")
                .operator(QueryCondition.Operator.IN)
                .value(Collections.emptyList())
                .build();

        QueryBuilderService queryBuilderService = new QueryBuilderServiceImpl(null);
        QueryBuilderService.QueryBuilder builder = queryBuilderService
                .buildConditionQuery(model, Collections.singletonList(condition));

        String sql = builder.getSql();
        List<Object> parameters = builder.getParameters();

        assertTrue(sql.contains("1=0"));
        assertEquals(0, parameters.size());
    }

    /**
     * 属性9扩展: NOT_IN查询空List处理
     * 
     * 对于任意NOT_IN查询,当List为空时,应该生成恒真条件 (1=1)
     * 
     * 验证: 需求 4.3
     */
    @Test
    @DisplayName("属性9扩展: NOT_IN查询空List处理 - 应该生成恒真条件")
    void property9_NotInQueryEmptyListHandling() {
        ModelDefinition model = createTestModel();
        QueryCondition condition = QueryCondition.builder()
                .fieldName("status")
                .operator(QueryCondition.Operator.NOT_IN)
                .value(Collections.emptyList())
                .build();

        QueryBuilderService queryBuilderService = new QueryBuilderServiceImpl(null);
        QueryBuilderService.QueryBuilder builder = queryBuilderService
                .buildConditionQuery(model, Collections.singletonList(condition));

        String sql = builder.getSql();
        List<Object> parameters = builder.getParameters();

        assertTrue(sql.contains("1=1"));
        assertEquals(0, parameters.size());
    }

    /**
     * 属性10: IN查询参数顺序保持
     * 
     * 对于任意IN查询,参数的顺序应该与输入List的顺序一致
     * 
     * 验证: 需求 4.4
     */
    @Test
    @DisplayName("属性10: IN查询参数顺序保持 - 参数顺序应该与List顺序一致")
    void property10_InQueryParameterOrderPreserved() {
        for (List<String> values : sampleStringLists()) {
            ModelDefinition model = createTestModel();
            QueryCondition condition = QueryCondition.builder()
                    .fieldName("status")
                    .operator(QueryCondition.Operator.IN)
                    .value(values)
                    .build();

            QueryBuilderService queryBuilderService = new QueryBuilderServiceImpl(null);
            QueryBuilderService.QueryBuilder builder = queryBuilderService
                    .buildConditionQuery(model, Collections.singletonList(condition));

            List<Object> parameters = builder.getParameters();
            for (int i = 0; i < values.size(); i++) {
                assertEquals(values.get(i), parameters.get(i));
            }
        }
    }

    /**
     * 属性11: IN查询数字类型处理
     * 
     * 对于任意数字类型的IN查询,应该正确处理Long类型的值
     * 
     * 验证: 需求 4.4
     */
    @Test
    @DisplayName("属性11: IN查询数字类型处理 - 应该正确处理Long类型")
    void property11_InQueryNumericTypeHandling() {
        for (List<Long> values : sampleLongLists()) {
            ModelDefinition model = createTestModel();
            QueryCondition condition = QueryCondition.builder()
                    .fieldName("id")
                    .operator(QueryCondition.Operator.IN)
                    .value(values)
                    .build();

            QueryBuilderService queryBuilderService = new QueryBuilderServiceImpl(null);
            QueryBuilderService.QueryBuilder builder = queryBuilderService
                    .buildConditionQuery(model, Collections.singletonList(condition));

            List<Object> parameters = builder.getParameters();
            assertEquals(values.size(), parameters.size());
            for (int i = 0; i < values.size(); i++) {
                assertEquals(values.get(i), parameters.get(i));
            }
        }
    }

    /**
     * 属性12: IN查询SQL格式一致性
     * 
     * 对于任意IN查询,生成的SQL应该包含必要的SQL关键字
     * 
     * 验证: 需求 4.4
     */
    @Test
    @DisplayName("属性12: IN查询SQL格式一致性 - SQL应该包含必要关键字")
    void property12_InQuerySqlFormatConsistency() {
        for (List<String> values : sampleStringLists()) {
            ModelDefinition model = createTestModel();
            QueryCondition condition = QueryCondition.builder()
                    .fieldName("status")
                    .operator(QueryCondition.Operator.IN)
                    .value(values)
                    .build();

            QueryBuilderService queryBuilderService = new QueryBuilderServiceImpl(null);
            QueryBuilderService.QueryBuilder builder = queryBuilderService
                    .buildConditionQuery(model, Collections.singletonList(condition));

            String sql = builder.getSql();
            assertTrue(sql.contains("select"));
            assertTrue(sql.contains("from"));
            assertTrue(sql.contains("where"));
            assertTrue(sql.contains("IN ("));
        }
    }

    private List<List<String>> sampleStringLists() {
        return List.of(
                List.of("draft"),
                List.of("draft", "published"),
                List.of("alpha", "beta", "gamma")
        );
    }

    private List<List<Long>> sampleLongLists() {
        return List.of(
                List.of(1L),
                List.of(1L, 2L),
                List.of(10L, 20L, 30L)
        );
    }

    /**
     * 创建测试模型
     */
    private ModelDefinition createTestModel() {
        return ModelDefinition.builder()
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
}
