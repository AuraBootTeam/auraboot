package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.dto.SchemaOperationResult;
import com.auraboot.framework.meta.event.ModelTablePublishedEvent;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.SchemaManagementService;
import com.auraboot.framework.permission.service.AutoPermissionAssignmentService;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.exception.PluginException;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Import orchestration contract; real transaction/DDL evidence belongs to native qualification. */
class PluginImportPublishedModelGuardTest {
    final PluginImportServiceImpl service=mock(PluginImportServiceImpl.class,CALLS_REAL_METHODS);
    final MetaModelService models=mock(MetaModelService.class);
    final SchemaManagementService schemas=mock(SchemaManagementService.class);
    final ApplicationEventPublisher events=mock(ApplicationEventPublisher.class);
    final AutoPermissionAssignmentService permissions=mock(AutoPermissionAssignmentService.class);

    PluginImportPublishedModelGuardTest(){
        ReflectionTestUtils.setField(service,"metaModelService",models);
        ReflectionTestUtils.setField(service,"schemaManagementService",schemas);
        ReflectionTestUtils.setField(service,"applicationEventPublisher",events);
        ReflectionTestUtils.setField(service,"autoPermissionAssignmentService",permissions);
        var model=MetaModelDTO.builder().code("fixture_guarded").pid("model-pid")
                .status(StatusConstants.PUBLISHED).modelType("entity").build();
        when(models.findByCode("fixture_guarded")).thenReturn(model);
        when(schemas.syncModelToTable(eq("fixture_guarded"),any()))
                .thenReturn(SchemaOperationResult.builder().success(true).build());
    }
    void run(boolean enabled){
        ReflectionTestUtils.invokeMethod(service,"autoPublishAndSyncModels",List.of("fixture_guarded"),
                ImportRequest.builder().autoPublishModels(enabled).build(),"fixture",7L);
    }
    @Test void publishedImportDispatchesExactGuardAfterSchemaBeforePermissions(){
        run(true);
        var ordered=inOrder(schemas,events,permissions);
        ordered.verify(schemas).syncModelToTable(eq("fixture_guarded"),any());
        ordered.verify(events).publishEvent(argThat((ModelTablePublishedEvent e)->"fixture_guarded".equals(e.getModelCode())));
        ordered.verify(permissions).registerPermissions("fixture_guarded","fixture",7L);
    }
    @Test void hookRejectionEscapesAndDoesNotRegisterPermissions(){
        doThrow(new IllegalStateException("immutable guard rejected schema"))
                .when(events).publishEvent(any(ModelTablePublishedEvent.class));
        assertThatThrownBy(()->run(true)).isInstanceOf(IllegalStateException.class).hasMessageContaining("immutable guard");
        verifyNoInteractions(permissions);
    }
    @Test void failedSchemaCannotProduceSuccessfulImportOrRunHooks(){
        when(schemas.syncModelToTable(eq("fixture_guarded"),any()))
                .thenReturn(SchemaOperationResult.builder().success(false).errorMessage("DDL unavailable").build());
        assertThatThrownBy(()->run(true)).isInstanceOf(PluginException.class).hasMessageContaining("DDL unavailable");
        verifyNoInteractions(events,permissions);
    }
    @Test void missingSchemaResultFailsClosed(){
        when(schemas.syncModelToTable(eq("fixture_guarded"),any())).thenReturn(null);
        assertThatThrownBy(()->run(true)).isInstanceOf(PluginException.class).hasMessageContaining("missing schema result");
        verifyNoInteractions(events,permissions);
    }
    @Test void disabledAutoPublishDoesNotTouchSchemaOrHooks(){
        run(false);verifyNoInteractions(models,schemas,events,permissions);
    }
    @Test void draftPublishRemainsTheAuthorityForItsOwnGuard(){
        var draft=MetaModelDTO.builder().pid("draft-pid").code("fixture_guarded").status(StatusConstants.DRAFT).build();
        when(models.findByCode("fixture_guarded")).thenReturn(draft);
        run(true);
        verify(models).publish(eq("draft-pid"),anyString(),eq(true),anyString());
        verifyNoInteractions(schemas,events,permissions);
    }
}
