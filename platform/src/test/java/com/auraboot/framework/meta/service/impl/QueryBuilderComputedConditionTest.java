package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.service.QueryBuilderService;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QueryBuilderComputedConditionTest {
    private final QueryBuilderService service=new QueryBuilderServiceImpl(null);
    private static final String EXPRESSION="CASE WHEN COALESCE(on_hold, FALSE) THEN 'paused' ELSE 'pending' END";
    private ModelDefinition model(String expression) {
        return ModelDefinition.builder().code("orders").tableName("mt_orders").fields(List.of(
            FieldDefinition.builder().code("queue").columnName("computed_queue").dataType("string")
                .virtualType("computed_readonly").computeExpression(expression).build(),
            FieldDefinition.builder().code("state").columnName("order_state").dataType("string").build())).build();
    }
    @Test void computedEqualityUsesTheDeclaredExpressionWithABoundValue() {
        String value="paused' OR TRUE --";
        var query=service.buildConditionQuery(model(EXPRESSION),List.of(QueryCondition.builder()
            .fieldName("queue").operator(QueryCondition.Operator.EQ).value(value).build()));
        assertTrue(query.getSql().contains("("+EXPRESSION+") = #{params.param0}"));
        assertFalse(query.getSql().contains(value));assertEquals(List.of(value),query.getParameters());
    }
    @Test void candidatePagesShareTheExpressionAndKeepTenantBoundsIndependent() {
        var query=service.buildConditionQuery(model(EXPRESSION),List.of(QueryCondition.builder()
            .fieldName("queue").operator(QueryCondition.Operator.IN).values(List.of("completed","paused")).build()));
        query.addCondition("tenant_id","EQ",17L).setLimit(10).setOffset(20);
        assertTrue(query.getSql().toLowerCase(Locale.ROOT).contains(("("+EXPRESSION+") IN (").toLowerCase(Locale.ROOT)));
        assertTrue(query.getSql().contains("tenant_id = #{params.param2}"));
        assertEquals(List.of("completed","paused",17L),query.getParameters());
    }
    @Test void physicalFieldsAndLegacyComputedFieldsWithoutSqlKeepTheirExistingMapping() {
        for(String field:List.of("state","queue")) {
            var query=service.buildConditionQuery(model(null),List.of(QueryCondition.builder()
                .fieldName(field).operator(QueryCondition.Operator.EQ).value("open").build()));
            assertTrue(query.getSql().contains((field.equals("state")?"order_state":"computed_queue")+" = #{params.param0}"));
        }
    }
    @Test void unknownFieldsAndUnsafeMetadataExpressionsCannotBecomePredicates() {
        assertThrows(RuntimeException.class,()->service.buildConditionQuery(model(EXPRESSION),List.of(QueryCondition.builder()
            .fieldName("queue OR TRUE").operator(QueryCondition.Operator.EQ).value("pending").build())));
        assertThrows(RuntimeException.class,()->service.buildConditionQuery(model("1; DROP TABLE mt_orders"),List.of(QueryCondition.builder()
            .fieldName("queue").operator(QueryCondition.Operator.EQ).value("pending").build())));
    }
    @Test void existingJsonbStorageRetainsItsTypedPredicateBeforeComputedMetadata() {
        var field=FieldDefinition.builder().code("queue").columnName("queue").dataType("string")
            .jsonbColumn("attributes").jsonbPath("queue").virtualType("computed_readonly").computeExpression(EXPRESSION).build();
        var model=ModelDefinition.builder().code("orders").tableName("mt_orders").fields(List.of(field)).build();
        var query=service.buildConditionQuery(model,List.of(QueryCondition.builder().fieldName("queue")
            .operator(QueryCondition.Operator.EQ).value("pending").build()));
        assertTrue(query.getSql().contains(field.getJsonbFilterExpression()+" = #{params.param0}"));
        assertFalse(query.getSql().contains(EXPRESSION));assertEquals(List.of("pending"),query.getParameters());
    }
}
