package com.auraboot.framework.permission.engine.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.engine.evaluator.DataScopeEvaluator;
import com.auraboot.framework.permission.engine.evaluator.FieldPermissionEvaluator;
import com.auraboot.framework.permission.engine.evaluator.PolicyEvaluator;
import com.auraboot.framework.permission.engine.evaluator.RecordShareEvaluator;
import com.auraboot.framework.permission.engine.evaluator.RolePermissionEvaluator;
import com.auraboot.framework.permission.engine.model.EvaluationStep;
import com.auraboot.framework.permission.engine.model.EvaluationVerdict;
import com.auraboot.framework.permission.engine.model.PermissionExplanation;
import com.auraboot.framework.permission.engine.model.PermissionResult;
import com.auraboot.framework.permission.service.PermissionAuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PermissionEvaluatorImplTest {

    @Mock private RolePermissionEvaluator rolePermissionEvaluator;
    @Mock private RecordShareEvaluator recordShareEvaluator;
    @Mock private DataScopeEvaluator dataScopeEvaluator;
    @Mock private PolicyEvaluator policyEvaluator;
    @Mock private FieldPermissionEvaluator fieldPermissionEvaluator;
    @Mock private PermissionAuditService permissionAuditService;

    @InjectMocks private PermissionEvaluatorImpl evaluator;

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void canOperateDeniesAndStopsWhenPolicyEvaluatorRejectsRecord() {
        Map<String, Object> record = Map.of("amount", 2000);
        when(rolePermissionEvaluator.evaluate(1L, "M", "approve"))
                .thenReturn(new EvaluationStep("RolePermission", EvaluationVerdict.ALLOW, "rbac ok"));
        when(recordShareEvaluator.evaluate(1L, "M", "approve", record))
                .thenReturn(new EvaluationStep("RecordShare", EvaluationVerdict.NOT_APPLICABLE, "not shared"));
        when(dataScopeEvaluator.evaluate(1L, "M", "approve", record))
                .thenReturn(new EvaluationStep("DataScope", EvaluationVerdict.NOT_APPLICABLE, "all"));
        when(policyEvaluator.evaluate(1L, "M", "approve", record))
                .thenReturn(new EvaluationStep(
                        "Policy",
                        EvaluationVerdict.DENY,
                        "Policy violations: dynamicAbac: permission_amount_guard expected matched=true but was false"));

        PermissionResult result = evaluator.canOperate(1L, "M", "approve", record);

        assertThat(result.granted()).isFalse();
        assertThat(result.reason()).contains("permission_amount_guard");
        assertThat(result.steps())
                .extracting(EvaluationStep::evaluatorName)
                .containsExactly("RolePermission", "RecordShare", "DataScope", "Policy");
        verify(fieldPermissionEvaluator, never()).evaluate(1L, "M", "approve", record);
    }

    @Test
    void canOperateAuditsPolicyValidationDenyWithoutLeakingRecordValue() {
        MetaContext.setContext(100L, 1L, "u", "t");
        Map<String, Object> record = Map.of(
                "id", 99L,
                "pid", "REQ-PID-99",
                "data", Map.of("salary", "1234567890"));
        when(rolePermissionEvaluator.evaluate(1L, "model.leave", "approve"))
                .thenReturn(new EvaluationStep("RolePermission", EvaluationVerdict.ALLOW, "rbac ok"));
        when(recordShareEvaluator.evaluate(1L, "model.leave", "approve", record))
                .thenReturn(new EvaluationStep("RecordShare", EvaluationVerdict.NOT_APPLICABLE, "not shared"));
        when(dataScopeEvaluator.evaluate(1L, "model.leave", "approve", record))
                .thenReturn(new EvaluationStep("DataScope", EvaluationVerdict.NOT_APPLICABLE, "all"));
        when(policyEvaluator.evaluate(1L, "model.leave", "approve", record))
                .thenReturn(new EvaluationStep(
                        "Policy",
                        EvaluationVerdict.DENY,
                        "Condition guard not satisfied: grant#900: "
                                + "Invalid permission ABAC policy at $.ruleBinding: "
                                + "record.data.salary is not available in permission ABAC fact catalog"));

        PermissionResult result = evaluator.canOperate(1L, "model.leave", "approve", record);

        assertThat(result.granted()).isFalse();
        ArgumentCaptor<PermissionExplanation> explanationCaptor =
                ArgumentCaptor.forClass(PermissionExplanation.class);
        verify(permissionAuditService).logEvaluation(eq(100L), explanationCaptor.capture());

        PermissionExplanation explanation = explanationCaptor.getValue();
        assertThat(explanation.finalResult()).isFalse();
        assertThat(explanation.recordId()).isEqualTo(99L);
        assertThat(explanation.recordPid()).isEqualTo("REQ-PID-99");
        assertThat(explanation.steps())
                .extracting(EvaluationStep::evaluatorName)
                .containsExactly("RolePermission", "RecordShare", "DataScope", "Policy");
        assertThat(explanation.steps().get(3).reason())
                .contains("record.data.salary")
                .contains("not available in permission ABAC fact catalog")
                .doesNotContain("1234567890");
        assertThat(explanation.toString()).doesNotContain("1234567890");
    }
}
