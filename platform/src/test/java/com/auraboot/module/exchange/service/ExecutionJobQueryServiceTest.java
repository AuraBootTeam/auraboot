package com.auraboot.module.exchange.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.entity.ExportTask;
import com.auraboot.framework.meta.mapper.ExportTaskMapper;
import com.auraboot.module.exchange.repository.DocumentArtifactJobRepository;
import com.auraboot.module.meta.excel.entity.ImportJob;
import com.auraboot.module.meta.excel.mapper.ImportJobMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class ExecutionJobQueryServiceTest {

    @Mock
    private ImportJobMapper importJobs;
    @Mock
    private ExportTaskMapper exportTasks;
    @Mock
    private DocumentArtifactJobRepository documentArtifacts;

    private ExecutionJobQueryService service;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(7L, 42L, "user-pid", "owner");
        lenient().when(documentArtifacts.findRecentForOwner(7L, 42L, 20)).thenReturn(List.of());
        lenient().when(documentArtifacts.findRecentForOwner(7L, 42L, 1)).thenReturn(List.of());
        service = new ExecutionJobQueryService(importJobs, exportTasks, documentArtifacts);
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void recentCombinesOwnerScopedPhysicalTasksAndNormalizesArtifacts() {
        ImportJob importJob = new ImportJob();
        importJob.setPid("import-1");
        importJob.setModelCode("customer");
        importJob.setStatus("completed");
        importJob.setTotalRows(3);
        importJob.setProcessedRows(3);
        importJob.setSuccessRows(2);
        importJob.setErrorRows(1);
        importJob.setErrorReportUrl("/api/meta/excel/import/customer/error-report/import-1");
        importJob.setCreatedAt(LocalDateTime.of(2026, 9, 30, 8, 0));

        ExportTask exportTask = new ExportTask();
        exportTask.setPid("export-1");
        exportTask.setQueryCode("customer-list");
        exportTask.setStatus(ExportTask.STATUS_COMPLETED);
        exportTask.setProgress(100);
        exportTask.setTotalRows(2L);
        exportTask.setProcessedRows(2L);
        exportTask.setFileKey("private-node-path.xlsx");
        exportTask.setFileSize(512L);
        exportTask.setFormat("EXCEL");
        exportTask.setRequestParams(new ObjectMapper().createObjectNode()
                .put("checksumSha256", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"));
        exportTask.setCreatedAt(Instant.parse("2026-09-30T09:00:00Z"));
        exportTask.setExpiresAt(Instant.now().plusSeconds(3600));

        when(importJobs.findRecentForOwner(7L, 42L, 20)).thenReturn(List.of(importJob));
        when(exportTasks.findRecentForOwner(7L, 42L, 20)).thenReturn(List.of(exportTask));

        var jobs = service.recent(20);

        assertThat(jobs).extracting("pid").containsExactly("export-1", "import-1");
        assertThat(jobs.get(0).getArtifact().getDownloadUrl())
                .isEqualTo("/api/meta/named-queries/export-tasks/export-1/download");
        assertThat(jobs.get(0).getArtifact().getDownloadUrl()).doesNotContain("private-node-path");
        assertThat(jobs.get(0).getArtifact().getChecksumSha256()).hasSize(64);
        assertThat(jobs.get(1).getStatus()).isEqualTo("completed_with_issues");
        assertThat(jobs.get(1).getArtifact().getKind()).isEqualTo("correction-workbook");
        verify(importJobs).findRecentForOwner(7L, 42L, 20);
        verify(exportTasks).findRecentForOwner(7L, 42L, 20);
    }

    @Test
    void recentMarksCompletedExportExpiredWhenArtifactLeaseElapsed() {
        ExportTask exportTask = new ExportTask();
        exportTask.setPid("export-expired");
        exportTask.setStatus(ExportTask.STATUS_COMPLETED);
        exportTask.setCreatedAt(Instant.parse("2026-09-29T09:00:00Z"));
        exportTask.setExpiresAt(Instant.now().minusSeconds(1));
        when(importJobs.findRecentForOwner(7L, 42L, 1)).thenReturn(List.of());
        when(exportTasks.findRecentForOwner(7L, 42L, 1)).thenReturn(List.of(exportTask));

        var job = service.recent(1).getFirst();

        assertThat(job.getStatus()).isEqualTo("expired");
        assertThat(job.getArtifact().isAvailable()).isFalse();
        assertThat(job.getArtifact().getDownloadUrl()).isNull();
    }

    @Test
    void recentGroupsAFormalDocumentPairIntoOneLogicalJob() {
        when(importJobs.findRecentForOwner(7L, 42L, 20)).thenReturn(List.of());
        when(exportTasks.findRecentForOwner(7L, 42L, 20)).thenReturn(List.of());
        when(documentArtifacts.findRecentForOwner(7L, 42L, 20)).thenReturn(List.of(
                documentRow("doc-xlsx", "xlsx", "/api/file/download/xlsx", "sum-xlsx"),
                documentRow("doc-pdf", "pdf", "/api/file/download/pdf", "sum-pdf")));

        var job = service.recent(20).getFirst();

        assertThat(job.getType()).isEqualTo("document");
        assertThat(job.getDefinitionCode()).isEqualTo("quote.customer.standard");
        assertThat(job.getSummary()).isEqualTo("正式文档 v3，2 个文件");
        assertThat(job.getDownloadUrl()).isEqualTo("/api/file/download/pdf");
        assertThat(job.getArtifacts()).extracting("format").containsExactly("xlsx", "pdf");
        assertThat(job.getArtifacts()).extracting("checksumSha256")
                .containsExactly("sum-xlsx", "sum-pdf");
    }

    private static DocumentArtifactJobRepository.Row documentRow(
            String pid, String format, String url, String checksum) {
        return DocumentArtifactJobRepository.Row.builder()
                .pid(pid)
                .jobKey("quote-1:3")
                .definitionCode("quote.customer.standard")
                .definitionVersion("1.0.0")
                .documentVersion(3)
                .businessVersion("11")
                .rendererVersion("renderer/1")
                .filename("quote." + format)
                .format(format)
                .downloadUrl(url)
                .fileSize(128L)
                .checksumSha256(checksum)
                .status("completed")
                .createdAt(Instant.parse("2026-09-30T10:00:00Z"))
                .build();
    }
}
