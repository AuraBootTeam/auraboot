package com.auraboot.framework.automation.executor.impl;

import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.executor.ActionExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CompositeActionExecutor.
 */
@ExtendWith(MockitoExtension.class)
class CompositeActionExecutorTest {

    @Mock
    private ActionExecutor executor1;

    @Mock
    private ActionExecutor executor2;

    private CompositeActionExecutor composite;

    @BeforeEach
    void setUp() {
        composite = new CompositeActionExecutor(List.of(executor1, executor2));
    }

    // =========================================================
    // supports()
    // =========================================================

    @Test
    void supports_delegatesToFirst_returnsTrue() {
        when(executor1.supports("create_record")).thenReturn(true);
        assertThat(composite.supports("create_record")).isTrue();
    }

    @Test
    void supports_delegatesToSecond_returnsTrue() {
        when(executor1.supports("send_notification")).thenReturn(false);
        when(executor2.supports("send_notification")).thenReturn(true);
        assertThat(composite.supports("send_notification")).isTrue();
    }

    @Test
    void supports_noneSupports_returnsFalse() {
        when(executor1.supports("unknown")).thenReturn(false);
        when(executor2.supports("unknown")).thenReturn(false);
        assertThat(composite.supports("unknown")).isFalse();
    }

    // =========================================================
    // execute()
    // =========================================================

    @Test
    void execute_firstExecutorMatches_delegatesAndReturnsResult() {
        AutomationAction action = buildAction("create_record");
        Map<String, Object> context = Map.of("recordId", "rec-001");
        Map<String, Object> expectedResult = Map.of("success", true);

        when(executor1.supports("create_record")).thenReturn(true);
        when(executor1.execute(action, context)).thenReturn(expectedResult);

        Object result = composite.execute(action, context);

        assertThat(result).isEqualTo(expectedResult);
        verify(executor1).execute(action, context);
        verifyNoInteractions(executor2);
    }

    @Test
    void execute_secondExecutorMatches_delegatesAndReturnsResult() {
        AutomationAction action = buildAction("send_notification");
        Map<String, Object> context = Map.of();
        Map<String, Object> expectedResult = Map.of("sentCount", 2);

        when(executor1.supports("send_notification")).thenReturn(false);
        when(executor2.supports("send_notification")).thenReturn(true);
        when(executor2.execute(action, context)).thenReturn(expectedResult);

        Object result = composite.execute(action, context);

        assertThat(result).isEqualTo(expectedResult);
        verify(executor1, never()).execute(any(), any());
        verify(executor2).execute(action, context);
    }

    @Test
    void execute_noSupportingExecutor_throwsUnsupportedOperationException() {
        AutomationAction action = buildAction("totally_unknown");

        when(executor1.supports("totally_unknown")).thenReturn(false);
        when(executor2.supports("totally_unknown")).thenReturn(false);

        assertThatThrownBy(() -> composite.execute(action, Map.of()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("totally_unknown");
    }

    // =========================================================
    // self-filtering
    // =========================================================

    @Test
    void constructor_filtersOutCompositeFromList() {
        // When another CompositeActionExecutor is in the list, it should be filtered
        CompositeActionExecutor inner = new CompositeActionExecutor(List.of(executor1));
        CompositeActionExecutor outer = new CompositeActionExecutor(List.of(executor1, inner));

        // outer should NOT delegate to inner (inner was filtered)
        when(executor1.supports("create_record")).thenReturn(true);
        when(executor1.execute(any(), any())).thenReturn(Map.of("ok", true));

        Object result = outer.execute(buildAction("create_record"), Map.of());

        assertThat(result).isEqualTo(Map.of("ok", true));
    }

    // =========================================================
    // Helper
    // =========================================================

    private AutomationAction buildAction(String type) {
        return AutomationAction.builder()
                .type(type)
                .config(Map.of())
                .build();
    }
}
