package com.auraboot.framework.permission.engine.evaluator;

import com.auraboot.framework.permission.engine.model.DataScopeCondition;
import com.auraboot.framework.permission.engine.model.EvaluationStep;
import com.auraboot.framework.permission.engine.model.EvaluationVerdict;
import com.auraboot.framework.permission.service.DataScopeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataScopeEvaluatorTest {

    @Mock private DataScopeService dataScopeService;
    @InjectMocks private DataScopeEvaluator evaluator;

    @Test
    void notConfiguredReturnsNotApplicable() {
        when(dataScopeService.resolveScope(1L, "M", "view")).thenReturn(DataScopeCondition.notConfigured());
        EvaluationStep s = evaluator.evaluate(1L, "M", "view", null);
        assertEquals(EvaluationVerdict.NOT_APPLICABLE, s.verdict());
    }

    @Test
    void allReturnsNotApplicable() {
        when(dataScopeService.resolveScope(1L, "M", "view")).thenReturn(DataScopeCondition.all());
        EvaluationStep s = evaluator.evaluate(1L, "M", "view", null);
        assertEquals(EvaluationVerdict.NOT_APPLICABLE, s.verdict());
    }

    @Test
    void noneReturnsDeny() {
        when(dataScopeService.resolveScope(1L, "M", "view")).thenReturn(DataScopeCondition.none());
        EvaluationStep s = evaluator.evaluate(1L, "M", "view", Map.of("id", 1));
        assertEquals(EvaluationVerdict.DENY, s.verdict());
    }

    @Test
    void selfScopeNonMapRecordNotApplicable() {
        when(dataScopeService.resolveScope(1L, "M", "view"))
                .thenReturn(new DataScopeCondition("self", "owner_id", 1L, null, List.of(), List.of()));
        EvaluationStep s = evaluator.evaluate(1L, "M", "view", "not-map");
        assertEquals(EvaluationVerdict.NOT_APPLICABLE, s.verdict());
    }

    @Test
    void selfScopeNullOwnerFieldDenies() {
        when(dataScopeService.resolveScope(1L, "M", "view"))
                .thenReturn(new DataScopeCondition("self", "owner_id", 1L, null, List.of(), List.of()));
        EvaluationStep s = evaluator.evaluate(1L, "M", "view", Map.of("name", "x"));
        assertEquals(EvaluationVerdict.DENY, s.verdict());
    }

    @Test
    void selfScopeOwnerMatchAllows() {
        when(dataScopeService.resolveScope(1L, "M", "view"))
                .thenReturn(new DataScopeCondition("self", "owner_id", 1L, null, List.of(), List.of()));
        EvaluationStep s = evaluator.evaluate(1L, "M", "view", Map.of("owner_id", 1));
        assertEquals(EvaluationVerdict.ALLOW, s.verdict());
    }

    @Test
    void selfScopeOwnerMismatchDenies() {
        when(dataScopeService.resolveScope(1L, "M", "view"))
                .thenReturn(new DataScopeCondition("self", "owner_id", 1L, null, List.of(), List.of()));
        EvaluationStep s = evaluator.evaluate(1L, "M", "view", Map.of("owner_id", "999"));
        assertEquals(EvaluationVerdict.DENY, s.verdict());
    }

    @Test
    void deptScopeNoDeptPidsDenies() {
        when(dataScopeService.resolveScope(1L, "M", "view"))
                .thenReturn(new DataScopeCondition("dept", "owner_id", 1L, "dept_pid", List.of(), List.of()));
        EvaluationStep s = evaluator.evaluate(1L, "M", "view", Map.of("dept_pid", "d1"));
        assertEquals(EvaluationVerdict.DENY, s.verdict());
    }

    @Test
    void deptScopeMatchingPidAllows() {
        when(dataScopeService.resolveScope(1L, "M", "view"))
                .thenReturn(new DataScopeCondition("dept_and_sub", "owner_id", 1L, "dept_pid", List.of("d1"), List.of()));
        EvaluationStep s = evaluator.evaluate(1L, "M", "view", Map.of("dept_pid", "d1"));
        assertEquals(EvaluationVerdict.ALLOW, s.verdict());
    }

    @Test
    void deptScopeMissingDeptFieldFallsBackToOwner() {
        when(dataScopeService.resolveScope(1L, "M", "view"))
                .thenReturn(new DataScopeCondition("dept", "owner_id", 1L, "dept_pid", List.of("d1"), List.of()));
        EvaluationStep s = evaluator.evaluate(1L, "M", "view", Map.of("owner_id", 1));
        assertEquals(EvaluationVerdict.ALLOW, s.verdict());
    }

    @Test
    void deptScopeNonMatchingDenies() {
        when(dataScopeService.resolveScope(1L, "M", "view"))
                .thenReturn(new DataScopeCondition("dept", "owner_id", 1L, "dept_pid", List.of("d1"), List.of()));
        EvaluationStep s = evaluator.evaluate(1L, "M", "view", Map.of("dept_pid", "d2"));
        assertEquals(EvaluationVerdict.DENY, s.verdict());
    }

    @Test
    void unknownScopeTypeNotApplicable() {
        when(dataScopeService.resolveScope(1L, "M", "view"))
                .thenReturn(new DataScopeCondition("custom", null, null, null, List.of(), List.of()));
        EvaluationStep s = evaluator.evaluate(1L, "M", "view", Map.of("id", 1));
        assertEquals(EvaluationVerdict.NOT_APPLICABLE, s.verdict());
    }

    @Test
    void getCondition_notConfiguredFallsBackToAll() {
        when(dataScopeService.resolveScope(1L, "M", "view")).thenReturn(DataScopeCondition.notConfigured());
        DataScopeCondition c = evaluator.getCondition(1L, "M", "view");
        assertEquals("all", c.scopeType());
    }

    @Test
    void getCondition_passesThroughExplicitScope() {
        DataScopeCondition self = new DataScopeCondition("self", "owner_id", 1L, null, List.of(), List.of());
        when(dataScopeService.resolveScope(1L, "M", "view")).thenReturn(self);
        assertSame(self, evaluator.getCondition(1L, "M", "view"));
    }
}
