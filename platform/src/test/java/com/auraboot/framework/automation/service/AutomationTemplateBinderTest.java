package com.auraboot.framework.automation.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.automation.dto.AutomationTemplateParameter;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.permission.engine.PermissionEvaluator;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutomationTemplateBinderTest {
    DynamicDataService data = mock(DynamicDataService.class);
    PermissionEvaluator permissions = mock(PermissionEvaluator.class);
    AutomationTemplateBinder binder = new AutomationTemplateBinder(data, permissions);
    AutomationTemplateParameter parameter = new AutomationTemplateParameter();
    Map<String, Object> flow = Map.of("nodes", List.of(Map.of("expression", "recordId == '{{template.target}}'")),
            "edges", List.of(Map.of("content", "recordId == '{{template.target}}'"), Map.of("content", "recordId != '{{template.target}}'")));
    @BeforeEach void setup() {
        MetaContext.setContext(11L, 22L, "user", "member"); MetaContext.setMemberId(33L);
        parameter.setKey("target"); parameter.setKind("record"); parameter.setLabel("Target record"); parameter.setModelCode("demo_task");
    }
    @AfterEach void clear() { MetaContext.clear(); }
    @Test void bindsAllThreeReferencesWithoutMutatingTemplate() {
        when(permissions.canAction(33L, "demo_task", "read")).thenReturn(true);
        when(data.getById("demo_task", "OWN-1")).thenReturn(Map.of("pid", "OWN-1"));
        var bound = binder.bind(flow, List.of(parameter), Map.of("target", "OWN-1"));
        assertThat(bound.toString().split("OWN-1", -1)).hasSize(4);
        assertThat(bound.toString()).doesNotContain("{{template.");
        assertThat(flow.toString()).contains("{{template.target}}");
        verify(data).getById("demo_task", "OWN-1");
    }
    @Test void missingValueDoesNotQueryOrWrite() {
        assertThatThrownBy(() -> binder.bind(flow, List.of(parameter), Map.of())).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(data, permissions);
    }
    @Test void noModelReadPermissionDoesNotReadRecord() {
        assertThatThrownBy(() -> binder.bind(flow, List.of(parameter), Map.of("target", "OTHER"))).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(data);
    }
    @Test void rejectsForeignOrUnreadableRecord() {
        when(permissions.canAction(33L, "demo_task", "read")).thenReturn(true);
        when(data.getById("demo_task", "OTHER")).thenThrow(new AccessDeniedException("Outside row scope"));
        assertThatThrownBy(() -> binder.bind(flow, List.of(parameter), Map.of("target", "OTHER"))).isInstanceOf(AccessDeniedException.class);
    }
    @Test void rejectsExpressionInjection() {
        assertThatThrownBy(() -> binder.bind(flow, List.of(parameter), Map.of("target", "' OR true"))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(data, permissions);
    }
    @Test void refusesUnresolvedLegacyImport() {
        assertThatThrownBy(() -> binder.bind(flow, null, null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void preservesNonParameterizedExports() {
        Map<String, Object> legacy = Map.of("nodes", List.of());
        assertThat(binder.bind(legacy, null, null)).isSameAs(legacy);
    }
    @Test void refusesExtraValues() {
        assertThatThrownBy(() -> binder.bind(flow, List.of(parameter), Map.of("target", "OWN", "extra", "OTHER"))).isInstanceOf(IllegalArgumentException.class);
    }
}
