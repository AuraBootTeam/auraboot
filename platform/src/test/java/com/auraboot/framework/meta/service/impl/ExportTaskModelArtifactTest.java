package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.infrastructure.storage.StorageProvider;
import com.auraboot.framework.meta.dto.DataExportRequest;
import com.auraboot.framework.meta.dto.ExportResult;
import com.auraboot.framework.meta.entity.ExportTask;
import com.auraboot.framework.meta.mapper.ExportTaskMapper;
import com.auraboot.framework.meta.mapper.NamedQueryMapper;
import com.auraboot.framework.meta.service.NamedQueryService;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
class ExportTaskModelArtifactTest {

    @Mock
    private ExportTaskMapper tasks;
    @Mock
    private NamedQueryMapper queries;
    @Mock
    private NamedQueryService namedQueryService;
    @Mock
    private StorageProvider storage;
    @Mock
    private DynamicDataService dynamicDataService;
    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void registerModelExportMovesTempFileToPrivateStorageWithChecksum() throws Exception {
        MetaContext.setContext(7L, 42L, "owner", "owner");
        ExportTaskService service = service();
        when(tasks.insert(any(ExportTask.class))).thenReturn(1);
        when(tasks.updateById(any(ExportTask.class))).thenReturn(1);
        Path source = Files.write(tempDir.resolve("customers.csv"), "code,name\nC1,Alice\n".getBytes());
        ExportResult result = ExportResult.builder()
                .success(true)
                .filePath(source.toString())
                .fileSize(Files.size(source))
                .recordCount(1L)
                .format("CSV")
                .rowSetDigest("rows-v1")
                .build();

        var dto = service.registerModelExport("customer", "customer:standard-export", DataExportRequest.builder()
                .format(DataExportRequest.ExportFormat.CSV)
                .build(), result);

        ArgumentCaptor<ExportTask> task = ArgumentCaptor.forClass(ExportTask.class);
        verify(tasks).insert(task.capture());
        assertThat(task.getValue().getQueryCode()).isEqualTo("model:customer");
        assertThat(task.getValue().getFileKey()).startsWith("exports/7/42/").endsWith(".csv");
        assertThat(task.getValue().getRequestParams().path("checksumSha256").asText()).hasSize(64);
        assertThat(task.getValue().getRequestParams().path("rowDigest").asText()).isEqualTo("rows-v1");
        verify(storage).upload(eq(task.getValue().getFileKey()), any(), eq(result.getFileSize()),
                eq("text/csv; charset=UTF-8"));
        assertThat(dto.getPid()).isEqualTo(task.getValue().getPid());
        assertThat(source).doesNotExist();
    }

    @Test
    void openModelArtifactRejectsAnotherModelEvenForOwner() {
        MetaContext.setContext(7L, 42L, "owner", "owner");
        ExportTask task = new ExportTask();
        task.setTenantId(7L);
        task.setCreatedBy(42L);
        task.setQueryCode("model:customer");
        task.setStatus(ExportTask.STATUS_COMPLETED);
        task.setFileKey("exports/7/42/task.csv");
        task.setExpiresAt(java.time.Instant.now().plusSeconds(60));
        when(tasks.findByPid("task")).thenReturn(task);

        assertThat(service().openModelArtifact("task", "supplier")).isNull();
    }

    @Test
    void openModelArtifactReturnsPrivateStreamWithoutExposingStorageKey() throws Exception {
        MetaContext.setContext(7L, 42L, "owner", "owner");
        ExportTask task = new ExportTask();
        task.setTenantId(7L);
        task.setCreatedBy(42L);
        task.setQueryCode("model:customer");
        task.setStatus(ExportTask.STATUS_COMPLETED);
        task.setFileKey("exports/7/42/task.csv");
        task.setFileSize(4L);
        task.setExpiresAt(java.time.Instant.now().plusSeconds(60));
        ObjectMapper json = new ObjectMapper();
        task.setRequestParams(json.createObjectNode()
                .set("request", json.valueToTree(DataExportRequest.builder()
                        .format(DataExportRequest.ExportFormat.CSV).build())));
        ((com.fasterxml.jackson.databind.node.ObjectNode) task.getRequestParams())
                .put("rowDigest", "rows-v1");
        when(tasks.findByPid("task")).thenReturn(task);
        Path current = Files.write(tempDir.resolve("reauthorized.csv"), "data".getBytes());
        when(dynamicDataService.exportData(eq("customer"), any())).thenReturn(ExportResult.builder()
                .success(true).filePath(current.toString()).rowSetDigest("rows-v1").build());
        when(storage.exists(task.getFileKey())).thenReturn(true);
        when(storage.download(task.getFileKey())).thenReturn(new ByteArrayInputStream("data".getBytes()));

        var artifact = service().openModelArtifact("task", "customer");

        assertThat(artifact).isNotNull();
        assertThat(artifact.extension()).isEqualTo(".csv");
        assertThat(artifact.content().readAllBytes()).isEqualTo("data".getBytes());
        assertThat(current).doesNotExist();
    }

    @Test
    void openModelArtifactRejectsAStaleAuthorizedRowSet() throws Exception {
        MetaContext.setContext(7L, 42L, "owner", "owner");
        ExportTask task = completedModelTask("rows-at-export");
        when(tasks.findByPid("task")).thenReturn(task);
        Path current = Files.write(tempDir.resolve("changed.csv"), "changed".getBytes());
        when(dynamicDataService.exportData(eq("customer"), any())).thenReturn(ExportResult.builder()
                .success(true).filePath(current.toString()).rowSetDigest("rows-now").build());

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> service().openModelArtifact("task", "customer"));
        assertThat(current).doesNotExist();
        verify(storage, org.mockito.Mockito.never()).download(any());
    }

    private static ExportTask completedModelTask(String digest) {
        ObjectMapper json = new ObjectMapper();
        ExportTask task = new ExportTask();
        task.setPid("task");
        task.setTenantId(7L);
        task.setCreatedBy(42L);
        task.setQueryCode("model:customer");
        task.setStatus(ExportTask.STATUS_COMPLETED);
        task.setFileKey("exports/7/42/task.csv");
        task.setFileSize(4L);
        task.setExpiresAt(java.time.Instant.now().plusSeconds(60));
        com.fasterxml.jackson.databind.node.ObjectNode metadata = json.createObjectNode();
        metadata.set("request", json.valueToTree(DataExportRequest.builder()
                .format(DataExportRequest.ExportFormat.CSV).build()));
        metadata.put("rowDigest", digest);
        task.setRequestParams(metadata);
        return task;
    }

    private ExportTaskService service() {
        return new ExportTaskService(tasks, queries, namedQueryService, new ObjectMapper(), storage,
                dynamicDataService);
    }
}
