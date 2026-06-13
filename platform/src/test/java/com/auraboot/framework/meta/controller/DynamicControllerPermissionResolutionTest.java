package com.auraboot.framework.meta.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.dto.PageMetaResponse;
import com.auraboot.framework.meta.dto.PageSchemaDTO;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.ModelFieldBindingService;
import com.auraboot.framework.meta.service.PageSchemaService;
import com.auraboot.framework.permission.service.UserPermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DynamicControllerPermissionResolutionTest {

    @Mock
    private UserPermissionService userPermissionService;
    @Mock
    private MetaModelService metaModelService;
    @Mock
    private PageSchemaService pageSchemaService;
    @Mock
    private ModelFieldBindingService modelFieldBindingService;

    @AfterEach
    void clearMetaContext() {
        if (MetaContext.exists()) {
            MetaContext.clear();
        }
    }

    @Test
    void resolvePermissionsUsesConvertedModelCodeForHyphenatedPageKeys() {
        MetaContext.setContext(1L, 100L, "U-100", "tester");
        DynamicController controller = new DynamicController();
        setField(controller, "userPermissionService", userPermissionService);

        when(userPermissionService.hasPermission(100L, "model.customer.read")).thenReturn(true);
        when(userPermissionService.hasPermission(100L, "model.customer.create")).thenReturn(true);
        when(userPermissionService.hasPermission(100L, "model.customer.update")).thenReturn(true);
        when(userPermissionService.hasPermission(100L, "model.customer.delete")).thenReturn(false);
        when(userPermissionService.hasPermission(100L, "model.customer.export")).thenReturn(false);

        PageMetaResponse.Permissions permissions = invokeResolvePermissions(controller, "customer-list");

        assertThat(permissions.isCanCreate()).isTrue();
        assertThat(permissions.isCanUpdate()).isTrue();
        assertThat(permissions.isCanDelete()).isFalse();
        assertThat(permissions.isCanExport()).isTrue();
        verify(userPermissionService, never()).hasPermission(100L, "model.customer-list.create");
        verify(userPermissionService, never()).hasPermission(100L, "model.customer-list.update");
    }

    @Test
    void getMeta_apiBackedPageWithoutDynamicModelReturnsSchemaOnlyMeta() {
        MetaContext.setContext(1L, 100L, "U-100", "tester");
        DynamicController controller = new DynamicController();
        setField(controller, "userPermissionService", userPermissionService);
        setField(controller, "metaModelService", metaModelService);
        setField(controller, "pageSchemaService", pageSchemaService);
        setField(controller, "modelFieldBindingService", modelFieldBindingService);

        PageSchemaDTO page = apiBackedDecisionDefinitionPage();
        when(pageSchemaService.findByPageKey("decisionops_definitions_list")).thenReturn(page);
        when(metaModelService.getModelDefinition("decisionops_definitions_list")).thenReturn(Optional.empty());
        when(metaModelService.getModelDefinition("decision_definition")).thenReturn(Optional.empty());

        ApiResponse<PageMetaResponse> response = controller.getMeta("decisionops_definitions_list");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData().getModelCode()).isEqualTo("decision_definition");
        assertThat(response.getData().getFields()).isEmpty();
        assertThat(response.getData().getSchema()).containsKeys("blocks", "layout", "title", "extension");
    }

    @Test
    void getFieldMeta_apiBackedPageWithoutDynamicModelReturnsEmptyFields() {
        DynamicController controller = new DynamicController();
        setField(controller, "metaModelService", metaModelService);
        setField(controller, "pageSchemaService", pageSchemaService);
        setField(controller, "modelFieldBindingService", modelFieldBindingService);

        PageSchemaDTO page = apiBackedDecisionDefinitionPage();
        when(pageSchemaService.findByPageKey("decisionops_definitions_list")).thenReturn(page);
        when(metaModelService.getModelDefinition("decisionops_definitions_list")).thenReturn(Optional.empty());
        when(metaModelService.findByCode("decision_definition")).thenReturn(null);

        ApiResponse<List<com.auraboot.framework.meta.dto.MetaFieldDTO>> response =
                controller.getFieldMeta("decisionops_definitions_list");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isEmpty();
    }

    private static PageSchemaDTO apiBackedDecisionDefinitionPage() {
        PageSchemaDTO page = new PageSchemaDTO();
        page.setPageKey("decisionops_definitions_list");
        page.setModelCode("decision_definition");
        page.setTitle(Map.of("zh-CN", "决策定义", "en-US", "Decision Definitions"));
        page.setLayout(Map.of("type", "stack"));
        page.setBlocks(List.of(Map.of("id", "decision_definition_table", "blockType", "table")));
        page.setExtension(Map.of(
                "skipFieldMeta", true,
                "dataSource", Map.of("type", "api", "endpoint", "/api/decision/definitions")
        ));
        return page;
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static PageMetaResponse.Permissions invokeResolvePermissions(
            DynamicController controller, String pageKey) {
        try {
            Method method = DynamicController.class.getDeclaredMethod("resolvePermissions", String.class);
            method.setAccessible(true);
            return (PageMetaResponse.Permissions) method.invoke(controller, pageKey);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(cause);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
