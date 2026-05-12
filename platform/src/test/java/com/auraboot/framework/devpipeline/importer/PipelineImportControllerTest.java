package com.auraboot.framework.devpipeline.importer;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PipelineImportControllerTest {

    @Mock
    private PipelineImportService importService;

    @Captor
    private ArgumentCaptor<PipelineImportRequest> requestCaptor;

    @Test
    void previewRejectsMissingPacketPath() {
        PipelineImportController controller = new PipelineImportController(importService);

        ApiResponse<PipelineImportPreview> response = controller.preview(
                new PipelineImportController.PipelineImportHttpRequest(" ", null, null, null, null));

        assertFalse(response.isSuccess());
        assertEquals("packetPath is required", response.getMessage());
        verifyNoInteractions(importService);
    }

    @Test
    void previewDelegatesWithDryRunAndMirrorDisabled() {
        PipelineImportPreview preview = new PipelineImportPreview("run-1", Map.of("dpl_pipeline_run", 1));
        when(importService.previewPacket(any())).thenReturn(preview);
        PipelineImportController controller = new PipelineImportController(importService);

        ApiResponse<PipelineImportPreview> response = controller.preview(
                new PipelineImportController.PipelineImportHttpRequest(
                        "/tmp/packet.json", false, "skip", true, "alice"));

        assertTrue(response.isSuccess());
        assertSame(preview, response.getData());
        verify(importService).previewPacket(requestCaptor.capture());

        PipelineImportRequest request = requestCaptor.getValue();
        assertEquals(Path.of("/tmp/packet.json"), request.packetPath());
        assertTrue(request.dryRun());
        assertEquals(ConflictStrategy.SKIP, request.conflictStrategy());
        assertFalse(request.finalizeMirror());
        assertEquals("alice", request.importedBy());
    }

    @Test
    void importPacketUsesDefaultsAndDelegates() {
        PipelineImportResult result = new PipelineImportResult(
                "run-1",
                "RUN-1",
                PipelineImportStatus.IMPORTED,
                Map.of("dpl_pipeline_run", 1),
                List.of(),
                Path.of("/mirror"),
                Path.of("/report.json"));
        when(importService.importFromPacket(any())).thenReturn(result);
        PipelineImportController controller = new PipelineImportController(importService);

        ApiResponse<PipelineImportResult> response = controller.importPacket(
                new PipelineImportController.PipelineImportHttpRequest(
                        "/tmp/packet.json", null, null, null, null));

        assertTrue(response.isSuccess());
        assertSame(result, response.getData());
        verify(importService).importFromPacket(requestCaptor.capture());

        PipelineImportRequest request = requestCaptor.getValue();
        assertEquals(Path.of("/tmp/packet.json"), request.packetPath());
        assertFalse(request.dryRun());
        assertEquals(ConflictStrategy.ERROR, request.conflictStrategy());
        assertTrue(request.finalizeMirror());
        assertEquals("owner", request.importedBy());
    }

    @Test
    void importPacketRejectsUnsupportedConflictStrategy() {
        PipelineImportController controller = new PipelineImportController(importService);

        ApiResponse<PipelineImportResult> response = controller.importPacket(
                new PipelineImportController.PipelineImportHttpRequest(
                        "/tmp/packet.json", null, "replace", null, null));

        assertFalse(response.isSuccess());
        assertEquals("Unsupported conflictStrategy: replace", response.getMessage());
        verifyNoInteractions(importService);
    }

    @Test
    void controllerRequiresModelManagePermission() {
        RequirePermission permission = PipelineImportController.class.getAnnotation(RequirePermission.class);

        assertNotNull(permission);
        assertEquals(MetaPermission.MODEL_MANAGE, permission.value());
    }

    @Test
    void springContextCreatesControllerAndImporterService() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ObjectMapper.class, (Supplier<ObjectMapper>) ObjectMapper::new);
            context.registerBean(DynamicDataService.class, () -> mock(DynamicDataService.class));
            context.registerBean(PipelineMirrorWriter.class, () -> mock(PipelineMirrorWriter.class));
            context.registerBean(PlatformTransactionManager.class, PipelineImportControllerTest::transactionManager);
            context.register(PipelineImportServiceImpl.class, PipelineImportController.class);

            context.refresh();

            assertNotNull(context.getBean(PipelineImportController.class));
            assertNotNull(context.getBean(PipelineImportService.class));
        }
    }

    private static PlatformTransactionManager transactionManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
    }
}
