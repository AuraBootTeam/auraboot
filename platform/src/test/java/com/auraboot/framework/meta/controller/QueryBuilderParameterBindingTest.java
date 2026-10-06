package com.auraboot.framework.meta.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.dto.QueryBuilderDTO;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.QueryBuilderReadProtection;
import com.auraboot.framework.meta.service.QueryBuilderService;
import com.auraboot.framework.permission.engine.PermissionEvaluator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QueryBuilderParameterBindingTest {
    private final MetaModelService models = mock(MetaModelService.class);
    private final QueryBuilderService builder = mock(QueryBuilderService.class);
    private final PermissionEvaluator permissions = mock(PermissionEvaluator.class);
    private final QueryBuilderReadProtection protection = mock(QueryBuilderReadProtection.class);
    private final QueryBuilderController controller = new QueryBuilderController(models, builder, permissions, protection);

    @BeforeEach void setup() {
        MetaContext.setContext(7L, 99L, "actor", "tester");
        when(permissions.canAction(99L, "customers", "read")).thenReturn(true);
        var field = new FieldDefinition();
        field.setCode("name");
        field.setColumnName("customer_name");
        var model = ModelDefinition.builder().tableName("mt_customers").fields(List.of(field)).build();
        when(models.getModelDefinition("customers")).thenReturn(Optional.of(model));
        var plan = new QueryBuilderReadProtection.Plan(Map.of("name", "customer_name"), null);
        when(protection.prepare(any(), anyMap(), eq("mt_customers"))).thenReturn(plan);
        when(protection.execute(any(), anyString(), anyMap())).thenReturn(List.of());
    }

    @AfterEach void clearContext() { MetaContext.clear(); }

    private QueryBuilderDTO query(String operator, Object value) {
        var dto = new QueryBuilderDTO();
        dto.setModelCode("customers");
        dto.setFields(List.of("name"));
        var filter = new QueryBuilderDTO.FilterCondition();
        filter.setFieldName("name");
        filter.setOperator(operator);
        filter.setValue(value);
        dto.setFilters(List.of(filter));
        return dto;
    }

    @Test void listOperatorsBindIndividualValuesWithoutEmbeddingUserText() {
        for (String operator : List.of("IN", "NOT_IN")) {
            clearInvocations(protection);
            String adversarial = "A') OR TRUE --";
            controller.execute(query(operator, List.of("A", adversarial)));
            verify(protection).execute(any(), argThat(sql ->
                    sql.contains("customer_name " + operator.replace('_', ' ') + " (#{params.f_0_0}, #{params.f_0_1})")
                            && !sql.contains(adversarial)),
                    eq(Map.of("f_0_0", "A", "f_0_1", adversarial)));
        }
    }

    @Test void emptyListOperatorsHaveExplicitPredicatesAndNoJdbcValues() {
        for (String operator : List.of("IN", "NOT_IN")) {
            clearInvocations(protection);
            controller.execute(query(operator, List.of()));
            String predicate = operator.equals("IN") ? "1 = 0" : "1 = 1";
            verify(protection).execute(any(), argThat(sql -> sql.contains("WHERE " + predicate)), eq(Map.of()));
        }
    }

    @Test void malformedListValuesFailBeforeProtectedDatabaseExecution() {
        for (String operator : List.of("IN", "NOT_IN")) {
            for (Object value : new Object[] { null, "A", List.of(List.of("A")), List.of(Map.of("name", "A")) }) {
                assertThatThrownBy(() -> controller.execute(query(operator, value)))
                        .isInstanceOf(MetaServiceException.class);
            }
        }
        verify(protection, never()).execute(any(), anyString(), anyMap());
    }

    @Test void softDeletePredicateFollowsModelConfigurationRatherThanTablePrefix() {
        for (String table : List.of("mt_customers", "custom_customer_records")) {
            for (boolean softDelete : List.of(false, true)) {
                var field = new FieldDefinition();
                field.setCode("name");
                field.setColumnName("customer_name");
                var model = ModelDefinition.builder().tableName(table)
                        .softDelete(softDelete).fields(List.of(field)).build();
                when(models.getModelDefinition("customers")).thenReturn(Optional.of(model));
                var plan = new QueryBuilderReadProtection.Plan(Map.of("name", "customer_name"), null);
                when(protection.prepare(any(), anyMap(), eq(table))).thenReturn(plan);
                clearInvocations(protection);
                controller.execute(query("EQ", "A"));
                verify(protection).execute(eq(plan), argThat(sql ->
                        sql.contains("FROM " + table)
                                && sql.contains("deleted_flag = FALSE") == softDelete),
                        eq(Map.of("f_0", "A")));
            }
        }
    }

    @Test void scalarEqualityRetainsItsOwnBinding() {
        controller.execute(query("EQ", "A"));
        verify(protection).execute(any(), argThat(sql -> sql.contains("customer_name = #{params.f_0}")),
                eq(Map.of("f_0", "A")));
    }
}
