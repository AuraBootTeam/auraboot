package com.auraboot.framework.devpipeline.importer;

import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PipelineImportServiceImplTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DynamicDataService dynamicDataService = mock(DynamicDataService.class);
    private final PipelineMirrorWriter mirrorWriter = mock(PipelineMirrorWriter.class);
    private final TransactionOperations transactionOperations = new TransactionOperations() {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            return action.doInTransaction(new SimpleTransactionStatus());
        }
    };

    @Test
    void previewPacket_validPacket_returnsCountsWithoutWriting() throws Exception {
        Path packetPath = writePacket();
        PipelineImportServiceImpl service = service();

        PipelineImportPreview preview = service.previewPacket(new PipelineImportRequest(
                packetPath, true, ConflictStrategy.ERROR, false, "owner"));

        assertThat(preview.runId()).isEqualTo("feat-20260512-importer");
        assertThat(preview.recordCounts()).containsEntry("dpl_pipeline_run", 1);
        assertThat(preview.recordCounts()).containsEntry("dpl_story", 1);
        verify(dynamicDataService, never()).create(any(), any());
    }

    @Test
    void importFromPacket_success_writesRecordsAndMirror() throws Exception {
        Path packetPath = writePacket();
        when(dynamicDataService.list(eq("dpl_pipeline_run"), any(DynamicQueryRequest.class)))
                .thenReturn(PaginationResult.empty(1, 1));
        when(dynamicDataService.create(eq("dpl_pipeline_run"), any()))
                .thenReturn(Map.of("pid", "dpl-run-pid-001"));
        when(dynamicDataService.create(eq("dpl_story"), any()))
                .thenReturn(Map.of("pid", "story-pid-001"));
        when(mirrorWriter.writeReadOnlyMirror(any(), eq("dpl-run-pid-001"), any(Instant.class)))
                .thenReturn(packetPath.getParent().getParent().getParent().resolve(".mirror.json"));
        PipelineImportServiceImpl service = service();

        PipelineImportResult result = service.importFromPacket(new PipelineImportRequest(
                packetPath, false, ConflictStrategy.ERROR, true, "owner"));

        assertThat(result.status()).isEqualTo(PipelineImportStatus.IMPORTED);
        assertThat(result.runPid()).isEqualTo("dpl-run-pid-001");
        assertThat(result.recordCounts()).containsEntry("dpl_story", 1);
        verify(dynamicDataService).create(eq("dpl_pipeline_run"), any());
        verify(dynamicDataService).create(eq("dpl_story"), any());
        verify(mirrorWriter).writeReadOnlyMirror(any(), eq("dpl-run-pid-001"), any(Instant.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void importFromPacket_resolvesChildRunReferencesToRunPid() throws Exception {
        Path packetPath = writePacket();
        List<Map<String, Object>> storyWrites = new ArrayList<>();
        when(dynamicDataService.list(eq("dpl_pipeline_run"), any(DynamicQueryRequest.class)))
                .thenReturn(PaginationResult.empty(1, 1));
        when(dynamicDataService.create(eq("dpl_pipeline_run"), any()))
                .thenReturn(Map.of("pid", "dpl-run-pid-001"));
        when(dynamicDataService.create(eq("dpl_story"), any())).thenAnswer(invocation -> {
            storyWrites.add((Map<String, Object>) invocation.getArgument(1));
            return Map.of("pid", "story-pid-001");
        });
        PipelineImportServiceImpl service = service();

        service.importFromPacket(new PipelineImportRequest(
                packetPath, false, ConflictStrategy.ERROR, false, "owner"));

        assertThat(storyWrites).hasSize(1);
        assertThat(storyWrites.get(0)).containsEntry("dpl_story_run_id", "dpl-run-pid-001");
    }

    @Test
    void importFromPacket_duplicateRunIdWithErrorStrategyDoesNotWrite() throws Exception {
        Path packetPath = writePacket();
        when(dynamicDataService.list(eq("dpl_pipeline_run"), any(DynamicQueryRequest.class)))
                .thenReturn(PaginationResult.of(List.of(Map.of("pid", "existing-run-pid")), 1L, 1, 1));
        PipelineImportServiceImpl service = service();

        PipelineImportResult result = service.importFromPacket(new PipelineImportRequest(
                packetPath, false, ConflictStrategy.ERROR, true, "owner"));

        assertThat(result.status()).isEqualTo(PipelineImportStatus.CONFLICT);
        assertThat(result.runPid()).isEqualTo("existing-run-pid");
        verify(dynamicDataService, never()).create(any(), any());
        verify(mirrorWriter, never()).writeReadOnlyMirror(any(), any(), any());
    }

    @Test
    void importFromPacket_mirrorFailureReturnsMirrorPendingAfterDbImport() throws Exception {
        Path packetPath = writePacket();
        when(dynamicDataService.list(eq("dpl_pipeline_run"), any(DynamicQueryRequest.class)))
                .thenReturn(PaginationResult.empty(1, 1));
        when(dynamicDataService.create(eq("dpl_pipeline_run"), any()))
                .thenReturn(Map.of("pid", "dpl-run-pid-001"));
        when(dynamicDataService.create(eq("dpl_story"), any()))
                .thenReturn(Map.of("pid", "story-pid-001"));
        when(mirrorWriter.writeReadOnlyMirror(any(), eq("dpl-run-pid-001"), any(Instant.class)))
                .thenThrow(new IllegalStateException("disk read-only"));
        PipelineImportServiceImpl service = service();

        PipelineImportResult result = service.importFromPacket(new PipelineImportRequest(
                packetPath, false, ConflictStrategy.ERROR, true, "owner"));

        assertThat(result.status()).isEqualTo(PipelineImportStatus.MIRROR_PENDING);
        assertThat(result.warnings()).anyMatch(warning -> warning.contains("finalize-plugin-import"));
        verify(dynamicDataService).create(eq("dpl_pipeline_run"), any());
        verify(dynamicDataService).create(eq("dpl_story"), any());
    }

    @Test
    void importFromPacket_unknownModelFailsBeforeDb() throws Exception {
        Path packetPath = writePacket(Map.of(
                "dpl_pipeline_run", List.of(Map.of(
                        "dpl_run_id", "feat-20260512-importer",
                        "dpl_run_title", "Importer",
                        "dpl_run_status", "merge_gate"
                )),
                "dpl_unknown_model", List.of(Map.of("bad", "value"))
        ));
        PipelineImportServiceImpl service = service();

        assertThatThrownBy(() -> service.importFromPacket(new PipelineImportRequest(
                packetPath, false, ConflictStrategy.ERROR, true, "owner")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown dev-pipeline record model");
        verify(dynamicDataService, never()).create(any(), any());
    }

    private PipelineImportServiceImpl service() {
        return new PipelineImportServiceImpl(objectMapper, dynamicDataService, mirrorWriter, transactionOperations);
    }

    private Path writePacket() throws Exception {
        return writePacket(Map.of(
                "dpl_pipeline_run", List.of(Map.of(
                        "dpl_run_id", "feat-20260512-importer",
                        "dpl_run_title", "Importer",
                        "dpl_run_status", "merge_gate"
                )),
                "dpl_story", List.of(Map.of(
                        "dpl_story_run_id", "feat-20260512-importer",
                        "dpl_story_id", "US-1",
                        "dpl_story_title", "Import story",
                        "dpl_story_status", "pass"
                ))
        ));
    }

    private Path writePacket(Map<String, Object> records) throws Exception {
        Path root = Files.createTempDirectory("dev-pipeline-importer-");
        Path runDir = root.resolve("pipeline").resolve("feat-20260512-importer");
        Path packetDir = runDir.resolve("evidence").resolve("import-packets");
        Files.createDirectories(packetDir);
        Path packetPath = packetDir.resolve("import-packet-20260512110000.json");
        Files.writeString(packetPath, objectMapper.writeValueAsString(Map.of(
                "schemaVersion", 1,
                "generatedAt", "2026-05-12T11:00:00+08:00",
                "runId", "feat-20260512-importer",
                "targetPlugin", Map.of(
                        "pluginId", "com.auraboot.dev-pipeline",
                        "namespace", "dpl"
                ),
                "writePolicy", "pending_plugin_import",
                "validation", Map.of("status", "pass"),
                "records", records
        )));
        return packetPath;
    }
}
