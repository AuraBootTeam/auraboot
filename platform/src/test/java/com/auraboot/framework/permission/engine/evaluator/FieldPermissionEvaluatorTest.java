package com.auraboot.framework.permission.engine.evaluator;

import com.auraboot.framework.permission.engine.model.EvaluationStep;
import com.auraboot.framework.permission.engine.model.EvaluationVerdict;
import com.auraboot.framework.permission.engine.model.FieldPermissionSet;
import com.auraboot.framework.permission.service.FieldPermissionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FieldPermissionEvaluatorTest {

    @Mock private FieldPermissionService fieldPermissionService;
    @InjectMocks private FieldPermissionEvaluator evaluator;

    @Test
    void noHiddenFieldsReturnsNotApplicable() {
        when(fieldPermissionService.getFieldPermissions(1L, "User"))
                .thenReturn(new FieldPermissionSet(Set.of("a", "b"), Set.of("a"), Set.of()));
        EvaluationStep step = evaluator.evaluate(1L, "User", "view", null);
        assertEquals(EvaluationVerdict.NOT_APPLICABLE, step.verdict());
    }

    @Test
    void withHiddenFieldsReturnsAllowWithSummary() {
        when(fieldPermissionService.getFieldPermissions(1L, "User"))
                .thenReturn(new FieldPermissionSet(Set.of("a", "b"), Set.of("a"), Set.of("secret")));
        EvaluationStep step = evaluator.evaluate(1L, "User", "view", null);
        assertEquals(EvaluationVerdict.ALLOW, step.verdict());
        assertTrue(step.reason().contains("hidden=1"));
        assertTrue(step.reason().contains("viewable=2"));
        assertTrue(step.reason().contains("editable=1"));
    }
}
