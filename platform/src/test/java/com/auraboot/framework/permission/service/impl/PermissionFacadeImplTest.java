package com.auraboot.framework.permission.service.impl;

import com.auraboot.framework.permission.engine.PermissionEvaluator;
import com.auraboot.framework.permission.engine.model.DataScopeCondition;
import com.auraboot.framework.permission.engine.model.FieldPermissionSet;
import com.auraboot.framework.permission.engine.model.PermissionExplanation;
import com.auraboot.framework.permission.engine.model.PermissionResult;
import com.auraboot.framework.permission.service.FieldPermissionService;
import com.auraboot.framework.permission.service.PermissionPolicyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PermissionFacadeImpl}.
 *
 * <p>Pure delegation facade — these tests verify each method routes to the
 * correct collaborator without modifying inputs/outputs.
 */
@ExtendWith(MockitoExtension.class)
class PermissionFacadeImplTest {

    @Mock
    private PermissionEvaluator evaluator;

    @Mock
    private PermissionPolicyService policyService;

    @Mock
    private FieldPermissionService fieldPermissionService;

    @InjectMocks
    private PermissionFacadeImpl facade;

    @Test
    void canActionDelegatesToEvaluator() {
        when(evaluator.canAction(1L, "model.user", "read")).thenReturn(true);

        boolean result = facade.canAction(1L, "model.user", "read");

        assertThat(result).isTrue();
        verify(evaluator).canAction(1L, "model.user", "read");
    }

    @Test
    void canActionReturnsFalseWhenEvaluatorDenies() {
        when(evaluator.canAction(1L, "model.user", "delete")).thenReturn(false);

        assertThat(facade.canAction(1L, "model.user", "delete")).isFalse();
    }

    @Test
    void getDataScopeConditionDelegatesToEvaluator() {
        DataScopeCondition expected = DataScopeCondition.all();
        when(evaluator.getDataScopeCondition(1L, "model.user", "read")).thenReturn(expected);

        DataScopeCondition actual = facade.getDataScopeCondition(1L, "model.user", "read");

        assertThat(actual).isSameAs(expected);
    }

    @Test
    void canOperateDelegatesToEvaluator() {
        Object record = new Object();
        PermissionResult expected = PermissionResult.allow(List.of());
        when(evaluator.canOperate(1L, "model.user", "update", record)).thenReturn(expected);

        PermissionResult actual = facade.canOperate(1L, "model.user", "update", record);

        assertThat(actual).isSameAs(expected);
        assertThat(actual.granted()).isTrue();
    }

    @Test
    void getEffectivePolicyDelegatesToPolicyService() {
        Map<String, Object> expected = Map.of("maxApprovalAmount", 1000);
        when(policyService.getEffectivePolicy(1L, "model.user.update")).thenReturn(expected);

        Map<String, Object> actual = facade.getEffectivePolicy(1L, "model.user.update");

        assertThat(actual).containsEntry("maxApprovalAmount", 1000);
    }

    @Test
    void getFieldPermissionsDelegatesToFieldService() {
        FieldPermissionSet expected = FieldPermissionSet.allAllowed(Set.of("name", "email"));
        when(fieldPermissionService.getFieldPermissions(1L, "model.user")).thenReturn(expected);

        FieldPermissionSet actual = facade.getFieldPermissions(1L, "model.user");

        assertThat(actual.viewableFields()).containsExactlyInAnyOrder("name", "email");
    }

    @Test
    void explainDelegatesToEvaluator() {
        PermissionExplanation expected = new PermissionExplanation(1L, "model.user", "read", 99L, true, List.of());
        when(evaluator.explain(1L, "model.user", "read", 99L)).thenReturn(expected);

        PermissionExplanation actual = facade.explain(1L, "model.user", "read", 99L);

        assertThat(actual).isSameAs(expected);
        assertThat(actual.finalResult()).isTrue();
    }
}
