package com.auraboot.framework.permission.engine.evaluator;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.engine.model.EvaluationStep;
import com.auraboot.framework.permission.engine.model.EvaluationVerdict;
import com.auraboot.framework.permission.service.RecordShareService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecordShareEvaluatorTest {

    @Mock private RecordShareService recordShareService;
    @InjectMocks private RecordShareEvaluator evaluator;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(99L, 1L, "p", "u");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void nullRecordIsNotApplicable() {
        EvaluationStep s = evaluator.evaluate(1L, "User", "view", null);
        assertEquals(EvaluationVerdict.NOT_APPLICABLE, s.verdict());
    }

    @Test
    void nonMapRecordIsNotApplicable() {
        EvaluationStep s = evaluator.evaluate(1L, "User", "view", "string");
        assertEquals(EvaluationVerdict.NOT_APPLICABLE, s.verdict());
    }

    @Test
    void recordWithoutIdIsNotApplicable() {
        EvaluationStep s = evaluator.evaluate(1L, "User", "view", Map.of("name", "x"));
        assertEquals(EvaluationVerdict.NOT_APPLICABLE, s.verdict());
    }

    @Test
    void recordIdAsNumberSharedReturnsAllow() {
        when(recordShareService.isShared(99L, "User", 5L, 1L)).thenReturn(true);
        EvaluationStep s = evaluator.evaluate(1L, "User", "view", Map.of("id", 5));
        assertEquals(EvaluationVerdict.ALLOW, s.verdict());
    }

    @Test
    void recordIdAsStringSharedReturnsAllow() {
        when(recordShareService.isShared(99L, "User", 7L, 1L)).thenReturn(true);
        EvaluationStep s = evaluator.evaluate(1L, "User", "view", Map.of("id", "7"));
        assertEquals(EvaluationVerdict.ALLOW, s.verdict());
    }

    @Test
    void recordPidSharedReturnsAllow() {
        when(recordShareService.isSharedByPid(99L, "User", "rec_7", 1L, "p")).thenReturn(true);

        EvaluationStep s = evaluator.evaluate(1L, "User", "view", Map.of("pid", "rec_7"));

        assertEquals(EvaluationVerdict.ALLOW, s.verdict());
        verify(recordShareService, never()).isShared(anyLong(), anyString(), anyLong(), anyLong());
    }

    @Test
    void recordNotSharedReturnsNotApplicable() {
        when(recordShareService.isShared(99L, "User", 5L, 1L)).thenReturn(false);
        EvaluationStep s = evaluator.evaluate(1L, "User", "view", Map.of("id", 5L));
        assertEquals(EvaluationVerdict.NOT_APPLICABLE, s.verdict());
    }

    @Test
    void invalidIdStringIsNotApplicable() {
        EvaluationStep s = evaluator.evaluate(1L, "User", "view", Map.of("id", "abc"));
        assertEquals(EvaluationVerdict.NOT_APPLICABLE, s.verdict());
    }
}
