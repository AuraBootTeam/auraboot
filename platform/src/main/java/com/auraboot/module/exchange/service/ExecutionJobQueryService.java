package com.auraboot.module.exchange.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.entity.ExportTask;
import com.auraboot.framework.meta.mapper.ExportTaskMapper;
import com.auraboot.module.exchange.dto.ExecutionJobDTO;
import com.auraboot.module.exchange.repository.DocumentArtifactJobRepository;
import com.auraboot.module.meta.excel.entity.ImportJob;
import com.auraboot.module.meta.excel.mapper.ImportJobMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;

/** Read-only logical projection; existing physical task tables remain authoritative. */
@Service
@RequiredArgsConstructor
public class ExecutionJobQueryService {

    private static final int MAX_LIMIT = 100;

    private final ImportJobMapper importJobMapper;
    private final ExportTaskMapper exportTaskMapper;
    private final DocumentArtifactJobRepository documentArtifactJobRepository;

    public List<ExecutionJobDTO> recent(int requestedLimit) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        if (tenantId == null || userId == null) {
            throw new IllegalStateException("Authenticated job owner is required");
        }
        int limit = Math.max(1, Math.min(requestedLimit, MAX_LIMIT));
        List<ExecutionJobDTO> jobs = new ArrayList<>();
        importJobMapper.findRecentForOwner(tenantId, userId, limit).stream()
                .map(this::fromImport)
                .forEach(jobs::add);
        exportTaskMapper.findRecentForOwner(tenantId, userId, limit).stream()
                .map(this::fromExport)
                .forEach(jobs::add);
        jobs.addAll(fromDocuments(documentArtifactJobRepository.findRecentForOwner(
                tenantId, userId, limit)));
        return jobs.stream()
                .sorted(Comparator.comparing(ExecutionJobDTO::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit)
                .toList();
    }

    private ExecutionJobDTO fromImport(ImportJob job) {
        long total = value(job.getTotalRows());
        long processed = value(job.getProcessedRows());
        long failed = value(job.getErrorRows());
        String status = normalizeImportStatus(job.getStatus(), failed);
        String definitionCode = job.getImportMode() != null
                && job.getImportMode().startsWith("DOCUMENT:")
                ? job.getImportMode().substring("DOCUMENT:".length())
                : job.getModelCode() + ":default-import";
        return ExecutionJobDTO.builder()
                .pid(job.getPid())
                .type("import")
                .definitionCode(definitionCode)
                .status(status)
                .progress(progress(total, processed, status))
                .totalUnits(total)
                .processedUnits(processed)
                .succeededUnits(value(job.getSuccessRows()))
                .failedUnits(failed)
                .format("xlsx")
                .summary(summary(value(job.getSuccessRows()), failed, total))
                .downloadUrl(job.getErrorReportUrl())
                .artifact(job.getErrorReportUrl() == null ? null : ExecutionJobDTO.Artifact.builder()
                        .kind("correction-workbook")
                        .format("xlsx")
                        .downloadUrl(job.getErrorReportUrl())
                        .available(true)
                        .build())
                .artifacts(job.getErrorReportUrl() == null ? List.of() : List.of(
                        ExecutionJobDTO.Artifact.builder()
                                .kind("correction-workbook")
                                .format("xlsx")
                                .downloadUrl(job.getErrorReportUrl())
                                .available(true)
                                .build()))
                .createdAt(toInstant(job.getCreatedAt()))
                .completedAt(toInstant(job.getCompletedAt()))
                .build();
    }

    private ExecutionJobDTO fromExport(ExportTask task) {
        Instant now = Instant.now();
        boolean modelExport = task.getQueryCode() != null && task.getQueryCode().startsWith("model:");
        String sourceCode = modelExport ? task.getQueryCode().substring("model:".length()) : task.getQueryCode();
        boolean available = ExportTask.STATUS_COMPLETED.equals(task.getStatus())
                && task.getFileKey() != null
                && task.getExpiresAt() != null
                && task.getExpiresAt().isAfter(now);
        String status = available ? "completed" : normalizeExportStatus(task, now);
        String downloadUrl = available
                ? modelExport
                    ? "/api/dynamic/" + sourceCode + "/exports/" + task.getPid() + "/download"
                    : "/api/meta/named-queries/export-tasks/" + task.getPid() + "/download"
                : null;
        String checksum = task.getRequestParams() == null
                ? null : task.getRequestParams().path("checksumSha256").textValue();
        String definitionCode = modelExport && task.getRequestParams() != null
                ? task.getRequestParams().path("profileCode").asText(sourceCode + ":default-export")
                : sourceCode;
        return ExecutionJobDTO.builder()
                .pid(task.getPid())
                .type("export")
                .definitionCode(definitionCode)
                .status(status)
                .progress(task.getProgress())
                .totalUnits(task.getTotalRows())
                .processedUnits(task.getProcessedRows())
                .succeededUnits(task.getProcessedRows())
                .failedUnits(0L)
                .format(task.getFormat())
                .summary(summary(value(task.getProcessedRows()), 0L, value(task.getTotalRows())))
                .downloadUrl(downloadUrl)
                .artifact(ExecutionJobDTO.Artifact.builder()
                        .kind("dataset")
                        .format(task.getFormat())
                        .downloadUrl(downloadUrl)
                        .size(task.getFileSize())
                        .checksumSha256(checksum)
                        .expiresAt(task.getExpiresAt())
                        .available(available)
                        .build())
                .artifacts(List.of(ExecutionJobDTO.Artifact.builder()
                        .kind("dataset")
                        .format(task.getFormat())
                        .downloadUrl(downloadUrl)
                        .size(task.getFileSize())
                        .checksumSha256(checksum)
                        .expiresAt(task.getExpiresAt())
                        .available(available)
                        .build()))
                .createdAt(task.getCreatedAt())
                .completedAt(task.getCompletedAt())
                .build();
    }

    private List<ExecutionJobDTO> fromDocuments(List<DocumentArtifactJobRepository.Row> rows) {
        Map<String, List<DocumentArtifactJobRepository.Row>> groups = new LinkedHashMap<>();
        for (DocumentArtifactJobRepository.Row row : rows) {
            String key = row.getJobKey() == null || row.getJobKey().isBlank()
                    ? row.getPid() : row.getJobKey();
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        return groups.values().stream().map(group -> {
            DocumentArtifactJobRepository.Row first = group.getFirst();
            List<ExecutionJobDTO.Artifact> artifacts = group.stream()
                    .map(row -> ExecutionJobDTO.Artifact.builder()
                            .kind("formal-document")
                            .format(row.getFormat())
                            .filename(row.getFilename())
                            .downloadUrl(row.getDownloadUrl())
                            .size(row.getFileSize())
                            .checksumSha256(row.getChecksumSha256())
                            .available("completed".equalsIgnoreCase(row.getStatus())
                                    && row.getDownloadUrl() != null)
                            .build())
                    .toList();
            ExecutionJobDTO.Artifact primary = artifacts.stream()
                    .filter(artifact -> "pdf".equalsIgnoreCase(artifact.getFormat()))
                    .findFirst().orElse(artifacts.getFirst());
            return ExecutionJobDTO.builder()
                    .pid(first.getPid())
                    .type("document")
                    .definitionCode(first.getDefinitionCode())
                    .status(first.getStatus() == null ? "failed" : first.getStatus().toLowerCase(Locale.ROOT))
                    .progress(100)
                    .totalUnits((long) artifacts.size())
                    .processedUnits((long) artifacts.size())
                    .succeededUnits((long) artifacts.size())
                    .failedUnits(0L)
                    .format(artifacts.stream().map(ExecutionJobDTO.Artifact::getFormat)
                            .sorted().reduce((left, right) -> left + "/" + right).orElse(null))
                    .summary("正式文档 v" + first.getDocumentVersion() + "，" + artifacts.size() + " 个文件")
                    .downloadUrl(primary.getDownloadUrl())
                    .artifact(primary)
                    .artifacts(artifacts)
                    .createdAt(first.getCreatedAt())
                    .completedAt(first.getCreatedAt())
                    .build();
        }).toList();
    }

    private static String normalizeImportStatus(String raw, long failedRows) {
        String status = raw == null ? "failed" : raw.toLowerCase(Locale.ROOT);
        return "completed".equals(status) && failedRows > 0 ? "completed_with_issues" : status;
    }

    private static String normalizeExportStatus(ExportTask task, Instant now) {
        if (ExportTask.STATUS_COMPLETED.equals(task.getStatus())
                && task.getExpiresAt() != null && !task.getExpiresAt().isAfter(now)) {
            return "expired";
        }
        return task.getStatus() == null ? "failed" : task.getStatus().toLowerCase(Locale.ROOT);
    }

    private static int progress(long total, long processed, String status) {
        if ("completed".equals(status) || "completed_with_issues".equals(status)) return 100;
        if (total <= 0) return 0;
        return (int) Math.min(100, Math.round(processed * 100.0 / total));
    }

    private static long value(Integer value) {
        return value == null ? 0L : value.longValue();
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }

    private static String summary(long succeeded, long failed, long total) {
        if (total <= 0) return "—";
        return failed > 0
                ? "成功 " + succeeded + "，失败 " + failed + "，共 " + total
                : "已处理 " + succeeded + " / " + total;
    }

    private static Instant toInstant(java.time.LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
