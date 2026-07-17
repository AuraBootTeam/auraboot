package com.auraboot.framework.permission.engine.evaluator;

import com.auraboot.framework.permission.engine.model.EvaluationStep;
import com.auraboot.framework.permission.engine.model.EvaluationVerdict;
import com.auraboot.framework.permission.service.UserPermissionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RolePermissionEvaluatorTest {

    @Mock private UserPermissionService userPermissionService;
    @InjectMocks private RolePermissionEvaluator evaluator;

    @Test
    void allowsWhenUserHasPermission() {
        when(userPermissionService.hasPermission(1L, "User:view")).thenReturn(true);
        EvaluationStep step = evaluator.evaluate(1L, "User", "view");
        assertEquals(EvaluationVerdict.ALLOW, step.verdict());
        assertTrue(step.reason().contains("User:view"));
    }

    @Test
    void deniesWhenUserLacksPermission() {
        when(userPermissionService.hasPermission(1L, "User:edit")).thenReturn(false);
        EvaluationStep step = evaluator.evaluate(1L, "User", "edit");
        assertEquals(EvaluationVerdict.DENY, step.verdict());
        assertTrue(step.reason().contains("User:edit"));
    }

    @Test
    void allowsRealModelPermissionCodeCandidate() {
        when(userPermissionService.hasPermission(1L, "wd_leave_request:read")).thenReturn(false);
        when(userPermissionService.hasPermission(1L, "wd_leave_request.read")).thenReturn(false);
        when(userPermissionService.hasPermission(1L, "model.wd_leave_request.read")).thenReturn(true);

        EvaluationStep step = evaluator.evaluate(1L, "wd_leave_request", "read");

        assertEquals(EvaluationVerdict.ALLOW, step.verdict());
        assertTrue(step.reason().contains("model.wd_leave_request.read"));
        verify(userPermissionService).hasPermission(1L, "wd_leave_request:read");
        verify(userPermissionService).hasPermission(1L, "wd_leave_request.read");
        verify(userPermissionService).hasPermission(1L, "model.wd_leave_request.read");
    }
}
