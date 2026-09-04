package com.auraboot.framework.meta.controller;

import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R7 closure: the {@code X-Base-Record-Version} precondition header sent by
 * mobile offline replay must reach the service compare-and-swap path as the
 * {@code _expectedVersion} payload token. Header-only clients (mobile) get
 * conflict-checked updates without touching their queued bodies.
 */
@ExtendWith(MockitoExtension.class)
class DynamicControllerBaseVersionHeaderTest {

    @Mock
    private DynamicDataService dynamicDataService;
    @Mock
    private MetaModelService metaModelService;

    private DynamicController controllerWithMockedServices() {
        DynamicController controller = new DynamicController();
        ReflectionTestUtils.setField(controller, "dynamicDataService", dynamicDataService);
        ReflectionTestUtils.setField(controller, "metaModelService", metaModelService);
        return controller;
    }

    @Test
    void headerTranslatesToExpectedVersionToken() {
        DynamicController controller = controllerWithMockedServices();
        when(metaModelService.getModelDefinition("p")).thenReturn(Optional.empty());
        Map<String, Object> result = new HashMap<>();
        when(dynamicDataService.update(anyString(), anyString(), any())).thenReturn(result);

        Map<String, Object> data = new HashMap<>();
        data.put("name", "Alpha");
        controller.update("p", "rec-1", data, 7L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(dynamicDataService).update(eq("p"), eq("rec-1"), captor.capture());
        assertThat(captor.getValue()).containsEntry("_expectedVersion", 7L);
        assertThat(captor.getValue()).containsEntry("name", "Alpha");
    }

    @Test
    void explicitPayloadTokenWinsOverHeader() {
        DynamicController controller = controllerWithMockedServices();
        when(metaModelService.getModelDefinition("p")).thenReturn(Optional.empty());
        when(dynamicDataService.update(anyString(), anyString(), any())).thenReturn(new HashMap<>());

        Map<String, Object> data = new HashMap<>();
        data.put("_expectedVersion", 3L);
        controller.update("p", "rec-1", data, 7L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(dynamicDataService).update(eq("p"), eq("rec-1"), captor.capture());
        assertThat(captor.getValue()).containsEntry("_expectedVersion", 3L);
    }

    @Test
    void absentHeaderLeavesPayloadUntouched() {
        DynamicController controller = controllerWithMockedServices();
        when(metaModelService.getModelDefinition("p")).thenReturn(Optional.empty());
        when(dynamicDataService.update(anyString(), anyString(), any())).thenReturn(new HashMap<>());

        Map<String, Object> data = new HashMap<>();
        data.put("name", "Alpha");
        controller.update("p", "rec-1", data, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(dynamicDataService).update(eq("p"), eq("rec-1"), captor.capture());
        assertThat(captor.getValue()).doesNotContainKey("_expectedVersion");
    }
}
