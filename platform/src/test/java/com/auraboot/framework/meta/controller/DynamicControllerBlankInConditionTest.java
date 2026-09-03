package com.auraboot.framework.meta.controller;

import com.auraboot.framework.meta.dto.QueryCondition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Blank IN / NOT_IN selections (a DSL filter bound to an empty state value, e.g.
 * {@code qo_ql_risk IN ${state.riskFilter}} before the user picks a value) must mean
 * "no filtering" — otherwise every row is filtered out the moment the page loads.
 */
class DynamicControllerBlankInConditionTest {

    private QueryCondition inCondition(Object... values) {
        QueryCondition condition = new QueryCondition();
        condition.setFieldName("qo_ql_risk");
        condition.setOperator(QueryCondition.Operator.IN);
        condition.setValues(new ArrayList<>(List.of(values)));
        return condition;
    }

    @Test
    void dropsInConditionWhenAllValuesAreBlank() {
        List<QueryCondition> result = DynamicController.dropBlankInConditions(
                new ArrayList<>(List.of(inCondition(""), inCondition("   "))));
        assertThat(result).isEmpty();
    }

    @Test
    void keepsEmptyInConditionSoRuntimePermissionFiltersFailClosed() {
        QueryCondition condition = inCondition();

        List<QueryCondition> result = DynamicController.dropBlankInConditions(
                new ArrayList<>(List.of(condition)));

        assertThat(result).containsExactly(condition);
    }

    @Test
    void keepsInConditionWithAtLeastOneRealValue() {
        List<QueryCondition> result = DynamicController.dropBlankInConditions(
                new ArrayList<>(List.of(inCondition("ok"))));
        assertThat(result).hasSize(1);
    }

    @Test
    void keepsNonInConditionsUntouched() {
        QueryCondition eq = new QueryCondition();
        eq.setFieldName("qo_quote_code");
        eq.setOperator(QueryCondition.Operator.EQ);
        eq.setValue("");

        List<QueryCondition> result = DynamicController.dropBlankInConditions(
                new ArrayList<>(List.of(eq)));

        assertThat(result).hasSize(1);
    }

    @Test
    void mixedListDropsOnlyTheBlankInCondition() {
        QueryCondition eq = new QueryCondition();
        eq.setFieldName("qo_quote_code");
        eq.setOperator(QueryCondition.Operator.EQ);
        eq.setValue("QO-1");

        QueryCondition blankIn = inCondition("  ");

        QueryCondition notIn = new QueryCondition();
        notIn.setFieldName("status");
        notIn.setOperator(QueryCondition.Operator.NOT_IN);
        notIn.setValues(new ArrayList<>(List.of("", " ")));

        List<QueryCondition> result = DynamicController.dropBlankInConditions(
                new ArrayList<>(List.of(eq, blankIn, notIn)));

        assertThat(result).containsExactly(eq);
    }
}
