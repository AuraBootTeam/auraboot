package com.auraboot.module.meta.excel;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.infrastructure.storage.StorageProvider;
import com.auraboot.framework.i18n.service.I18nService;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.module.meta.excel.entity.ImportJob;
import com.auraboot.module.meta.excel.mapper.ImportJobMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellCopyPolicy;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Creates tenant-scoped correction workbooks for failed Excel import rows.
 *
 * <p>The first sheet retains the original import headers and exactly the rows that still need to
 * be imported: all rows after a rejected precheck, failed plus unprocessed rows after a stopped
 * import, or failed rows only after a continue-on-error import. This prevents both data loss and
 * duplicate writes. A second sheet provides human-readable error details and never participates
 * in re-import.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExcelImportErrorReportService {

    static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String ERROR_SHEET_NAME = "Import errors";
    private static final String REPORT_URL_PREFIX = "/api/meta/excel/import/";

    private final ImportJobMapper importJobMapper;
    private final StorageProvider storageProvider;
    private final MetaModelService metaModelService;
    private final I18nService i18nService;

    @Value("${auraboot.excel-import.error-report-retention-days:7}")
    private int retentionDays = 7;

    @Value("${auraboot.excel-import.error-report-cleanup-batch-size:100}")
    private int cleanupBatchSize = 100;

    public record ReportRegistration(String taskId, String downloadUrl) {
    }

    public record ReportDownload(InputStream content, String fileName) {
    }

    /** Which source rows must remain in the correction workbook to avoid loss or duplication. */
    public enum RowSelection {
        /** Validation rejected the whole file before writes; every row still needs importing. */
        ALL_ROWS,
        /** Import stopped at the first error; retain that row and every unprocessed row after it. */
        FROM_FIRST_ERROR,
        /** Import continued after errors; successful rows must not be retried. */
        ERROR_ROWS
    }

    /**
     * Create a completed durable import job for a synchronous or validation-only failure.
     */
    public ReportRegistration createReport(String modelCode, String fileName, String mode,
                                           byte[] sourceWorkbook,
                                           List<ImportValidationError> errors,
                                           int totalRows, int successRows, String locale,
                                           RowSelection rowSelection) {
        Long tenantId = requireContextValue(MetaContext.getCurrentTenantId(), "tenant");
        Long userId = requireContextValue(MetaContext.getCurrentUserId(), "user");
        String taskId = UniqueIdGenerator.generate();
        String downloadUrl = reportUrl(modelCode, taskId);
        String storageKey = storageKey(tenantId, userId, taskId);
        byte[] correctionWorkbook = buildCorrectionWorkbook(
                modelCode, sourceWorkbook, errors, locale, rowSelection);

        upload(storageKey, correctionWorkbook);
        try {
            ImportJob job = completedJob(taskId, tenantId, userId, modelCode, fileName, mode,
                    totalRows, successRows, errorCount(errors), downloadUrl);
            if (importJobMapper.insert(job) != 1 || job.getId() == null) {
                throw new BusinessException("Failed to persist the import error report");
            }
        } catch (RuntimeException persistenceError) {
            // CATCH: non-transactional storage compensation prevents an orphaned private object
            // when the durable import-job row cannot be created.
            deleteAfterFailedPersistence(storageKey, persistenceError);
            throw persistenceError;
        }
        return new ReportRegistration(taskId, downloadUrl);
    }

    /**
     * Attach a correction workbook to the durable job already created by an asynchronous import.
     */
    public String attachReport(Long jobId, String taskId, String modelCode, byte[] sourceWorkbook,
                               List<ImportValidationError> errors, String locale,
                               RowSelection rowSelection) {
        Long tenantId = requireContextValue(MetaContext.getCurrentTenantId(), "tenant");
        Long userId = requireContextValue(MetaContext.getCurrentUserId(), "user");
        ImportJob job = importJobMapper.selectById(jobId);
        if (job == null
                || !Objects.equals(job.getPid(), taskId)
                || !Objects.equals(job.getTenantId(), tenantId)
                || !Objects.equals(job.getCreatedBy(), userId)
                || !Objects.equals(job.getModelCode(), modelCode)) {
            throw new BusinessException("Import task is not available for the current user");
        }

        String storageKey = storageKey(tenantId, userId, taskId);
        String downloadUrl = reportUrl(modelCode, taskId);
        byte[] correctionWorkbook = buildCorrectionWorkbook(
                modelCode, sourceWorkbook, errors, locale, rowSelection);
        upload(storageKey, correctionWorkbook);
        try {
            job.setErrorReportUrl(downloadUrl);
            job.setUpdatedAt(utcNow());
            if (importJobMapper.updateById(job) != 1) {
                throw new BusinessException("Failed to attach the import error report");
            }
        } catch (RuntimeException persistenceError) {
            // CATCH: non-transactional storage compensation keeps the DB row and private object
            // from disagreeing when the report URL update fails.
            deleteAfterFailedPersistence(storageKey, persistenceError);
            throw persistenceError;
        }
        return downloadUrl;
    }

    /**
     * Resolve a report only when tenant, creator, model, and public task id all match.
     */
    public ReportDownload findDownload(String modelCode, String taskId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        if (tenantId == null || userId == null) {
            return null;
        }
        ImportJob job = importJobMapper.selectOne(Wrappers.<ImportJob>lambdaQuery()
                .eq(ImportJob::getPid, taskId)
                .eq(ImportJob::getTenantId, tenantId)
                .eq(ImportJob::getCreatedBy, userId)
                .eq(ImportJob::getModelCode, modelCode));
        if (job == null || job.getErrorReportUrl() == null
                || !job.getErrorReportUrl().equals(reportUrl(modelCode, taskId))
                || isReportExpired(job)) {
            return null;
        }
        String key = storageKey(tenantId, userId, taskId);
        if (!storageProvider.exists(key)) {
            return null;
        }
        return new ReportDownload(storageProvider.download(key),
                modelCode + "-import-errors.xlsx");
    }

    public boolean isReportExpired(ImportJob job) {
        if (job == null || job.getCompletedAt() == null) {
            return false;
        }
        return job.getCompletedAt().isBefore(retentionCutoff());
    }

    /**
     * Delete expired private report objects while preserving the durable import history row.
     */
    @Scheduled(
            initialDelayString = "${auraboot.excel-import.error-report-cleanup-initial-delay-ms:300000}",
            fixedDelayString = "${auraboot.excel-import.error-report-cleanup-delay-ms:3600000}"
    )
    public int cleanupExpiredReports() {
        List<ImportJob> expired = importJobMapper.findExpiredReports(
                retentionCutoff(), Math.max(1, cleanupBatchSize));
        int cleaned = 0;
        for (ImportJob job : expired) {
            String key = storageKey(job.getTenantId(), job.getCreatedBy(), job.getPid());
            try {
                storageProvider.delete(key);
                if (importJobMapper.clearErrorReport(job.getId(), utcNow()) == 1) {
                    cleaned++;
                } else {
                    log.warn("Expired import report object was removed but its job pointer was "
                            + "already changed: task={}", job.getPid());
                }
            } catch (RuntimeException cleanupError) {
                // CATCH: non-transactional per-object cleanup must leave this report eligible
                // for the next sweep without blocking other tenants' expired reports.
                log.error("Failed to clean expired import error report: task={}",
                        job.getPid(), cleanupError);
            }
        }
        if (cleaned > 0) {
            log.info("Cleaned {} expired Excel import error reports older than {} days",
                    cleaned, effectiveRetentionDays());
        }
        return cleaned;
    }

    byte[] buildCorrectionWorkbook(String modelCode, byte[] sourceWorkbook,
                                   List<ImportValidationError> errors) {
        return buildCorrectionWorkbook(modelCode, sourceWorkbook, errors,
                "zh-CN", RowSelection.ERROR_ROWS);
    }

    byte[] buildCorrectionWorkbook(String modelCode, byte[] sourceWorkbook,
                                   List<ImportValidationError> errors, String locale,
                                   RowSelection rowSelection) {
        if (sourceWorkbook == null || sourceWorkbook.length == 0) {
            throw new BusinessException("The uploaded workbook is empty");
        }
        List<ImportValidationError> safeErrors = errors == null ? List.of() : errors;
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(sourceWorkbook));
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new BusinessException("The uploaded workbook has no worksheets");
            }
            removePreviousErrorSheets(workbook);
            Sheet importSheet = workbook.getSheetAt(0);
            List<FieldDefinition> fieldDefinitions = metaModelService.getModelFields(modelCode);
            Map<String, String> fieldLabels = fieldLabels(fieldDefinitions);
            Map<String, Integer> fieldColumns = resolveFieldColumns(importSheet, fieldDefinitions);

            Set<Integer> failedRows = failedRows(safeErrors);
            boolean workbookLevelFailure = safeErrors.stream()
                    .anyMatch(error -> error.getRowNumber() <= 1);
            boolean keepAllRows = workbookLevelFailure || rowSelection == RowSelection.ALL_ROWS;
            FilteredSheet filtered = keepAllRows
                    ? new FilteredSheet(importSheet, identityRowMapping(importSheet))
                    : retainSelectedRows(workbook, importSheet,
                            selectedRows(importSheet, failedRows, rowSelection));
            importSheet = filtered.sheet();

            annotateFailedCells(workbook, importSheet, safeErrors, fieldColumns,
                    filtered.reportRowByOriginalRow(), workbookLevelFailure,
                    fieldLabels, locale);
            appendErrorDetails(workbook, safeErrors, fieldLabels, locale);
            importSheet.createFreezePane(0, 1);
            workbook.setActiveSheet(0);
            workbook.setForceFormulaRecalculation(true);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException workbookError) {
            throw new BusinessException(
                    ResponseCode.BUSINESS_ERROR,
                    "Failed to create the import error report",
                    workbookError);
        }
    }

    private ImportJob completedJob(String taskId, Long tenantId, Long userId, String modelCode,
                                   String fileName, String mode, int totalRows, int successRows,
                                   int errorRows, String downloadUrl) {
        LocalDateTime now = utcNow();
        ImportJob job = new ImportJob();
        job.setPid(taskId);
        job.setTenantId(tenantId);
        job.setModelCode(modelCode);
        job.setFileName(fileName);
        job.setStatus(StatusConstants.COMPLETED);
        job.setTotalRows(totalRows);
        job.setProcessedRows(totalRows);
        job.setSuccessRows(successRows);
        job.setErrorRows(errorRows);
        job.setImportMode(normalizeMode(mode));
        job.setErrorReportUrl(downloadUrl);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        job.setCompletedAt(now);
        job.setCreatedBy(userId);
        job.setDeletedFlag(false);
        return job;
    }

    private void upload(String storageKey, byte[] content) {
        storageProvider.upload(storageKey, new ByteArrayInputStream(content), content.length,
                XLSX_CONTENT_TYPE);
    }

    private void deleteAfterFailedPersistence(String storageKey, RuntimeException persistenceError) {
        try {
            storageProvider.delete(storageKey);
        } catch (RuntimeException cleanupError) {
            // CATCH: non-transactional best-effort cleanup; retain both failures for operators.
            persistenceError.addSuppressed(cleanupError);
            log.error("Failed to remove orphaned import error report object: key={}",
                    storageKey, cleanupError);
        }
    }

    private void removePreviousErrorSheets(XSSFWorkbook workbook) {
        for (int index = workbook.getNumberOfSheets() - 1; index >= 1; index--) {
            if (ERROR_SHEET_NAME.equalsIgnoreCase(workbook.getSheetName(index))) {
                workbook.removeSheetAt(index);
            }
        }
    }

    private Set<Integer> failedRows(List<ImportValidationError> errors) {
        Set<Integer> rows = new LinkedHashSet<>();
        for (ImportValidationError error : errors) {
            if (error.getRowNumber() >= 2) {
                rows.add(error.getRowNumber());
            }
        }
        return rows;
    }

    private Map<Integer, Integer> identityRowMapping(Sheet sheet) {
        Map<Integer, Integer> rows = new LinkedHashMap<>();
        for (int excelRow = 2; excelRow <= sheet.getLastRowNum() + 1; excelRow++) {
            rows.put(excelRow, excelRow);
        }
        return rows;
    }

    private record FilteredSheet(Sheet sheet, Map<Integer, Integer> reportRowByOriginalRow) {
    }

    private Set<Integer> selectedRows(Sheet source, Set<Integer> failedRows,
                                      RowSelection selection) {
        if (selection == RowSelection.FROM_FIRST_ERROR && !failedRows.isEmpty()) {
            int firstError = failedRows.stream().min(Integer::compareTo).orElse(2);
            Set<Integer> rows = new LinkedHashSet<>();
            for (int row = firstError; row <= source.getLastRowNum() + 1; row++) {
                rows.add(row);
            }
            return rows;
        }
        return failedRows;
    }

    private FilteredSheet retainSelectedRows(XSSFWorkbook workbook, Sheet source,
                                             Set<Integer> selectedRows) {
        List<Integer> orderedRows = selectedRows.stream()
                .filter(row -> row <= source.getLastRowNum() + 1)
                .sorted()
                .toList();
        int sourceIndex = workbook.getSheetIndex(source);
        String sourceName = source.getSheetName();
        String temporaryName = uniqueSheetName(workbook, "Correction rows");
        var filtered = workbook.createSheet(temporaryName);
        CellCopyPolicy copyPolicy = new CellCopyPolicy.Builder()
                .cellValue(true)
                .cellStyle(true)
                .cellFormula(true)
                .copyHyperlink(true)
                .rowHeight(true)
                .build();
        if (source.getRow(0) != null) {
            XSSFRow targetHeader = filtered.createRow(0);
            targetHeader.copyRowFrom(source.getRow(0), copyPolicy);
        }
        Map<Integer, Integer> mapping = new LinkedHashMap<>();
        int targetRowIndex = 1;
        for (Integer originalRow : orderedRows) {
            int sourceRowIndex = originalRow - 1;
            if (source.getRow(sourceRowIndex) != null) {
                XSSFRow targetRow = filtered.createRow(targetRowIndex);
                targetRow.copyRowFrom(source.getRow(sourceRowIndex), copyPolicy);
                mapping.put(originalRow, targetRowIndex + 1);
                targetRowIndex++;
            }
        }
        Row sourceHeader = source.getRow(0);
        if (sourceHeader != null) {
            for (int column = 0; column < sourceHeader.getLastCellNum(); column++) {
                filtered.setColumnWidth(column, source.getColumnWidth(column));
                filtered.setColumnHidden(column, source.isColumnHidden(column));
            }
        }
        workbook.removeSheetAt(sourceIndex);
        workbook.setSheetName(workbook.getSheetIndex(filtered), sourceName);
        workbook.setSheetOrder(sourceName, 0);
        return new FilteredSheet(workbook.getSheetAt(0), mapping);
    }

    private String uniqueSheetName(XSSFWorkbook workbook, String baseName) {
        String name = baseName;
        int suffix = 2;
        while (workbook.getSheet(name) != null) {
            name = baseName + " " + suffix;
            suffix++;
        }
        return name;
    }

    private Map<String, Integer> resolveFieldColumns(Sheet sheet,
                                                     List<FieldDefinition> fieldDefinitions) {
        Row header = sheet.getRow(0);
        if (header == null) {
            return Map.of();
        }
        DataFormatter formatter = new DataFormatter();
        List<String> rawHeaders = new ArrayList<>();
        for (int column = 0; column < header.getLastCellNum(); column++) {
            Cell cell = header.getCell(column);
            rawHeaders.add(cell == null ? "" : formatter.formatCellValue(cell).trim());
        }
        Map<String, String> mapping = ExcelImportService.resolveHeaderMapping(
                rawHeaders, fieldDefinitions == null ? List.of() : fieldDefinitions);
        Map<String, Integer> columns = new HashMap<>();
        for (int column = 0; column < rawHeaders.size(); column++) {
            String rawHeader = rawHeaders.get(column);
            if (!rawHeader.isBlank()) {
                columns.putIfAbsent(mapping.getOrDefault(rawHeader, rawHeader), column);
            }
        }
        return columns;
    }

    private void annotateFailedCells(XSSFWorkbook workbook, Sheet sheet,
                                     List<ImportValidationError> errors,
                                     Map<String, Integer> fieldColumns,
                                     Map<Integer, Integer> reportRowByOriginalRow,
                                     boolean workbookLevelFailure,
                                     Map<String, String> fieldLabels,
                                     String locale) {
        Map<String, List<String>> messagesByCell = new LinkedHashMap<>();
        for (ImportValidationError error : errors) {
            Integer reportExcelRow = error.getRowNumber() <= 1 && workbookLevelFailure
                    ? 1 : reportRowByOriginalRow.get(error.getRowNumber());
            if (reportExcelRow == null) {
                continue;
            }
            int column = error.getFieldCode() == null
                    ? 0 : fieldColumns.getOrDefault(error.getFieldCode(), 0);
            messagesByCell.computeIfAbsent(reportExcelRow + ":" + column,
                    ignored -> new ArrayList<>()).add(localizeErrorMessage(
                            locale, error.getMessage(), error.getFieldCode() == null
                                    ? "" : fieldLabels.getOrDefault(
                                            error.getFieldCode(), error.getFieldCode())));
        }

        Map<Short, CellStyle> warningStyles = new HashMap<>();
        var drawing = sheet.createDrawingPatriarch();
        for (Map.Entry<String, List<String>> entry : messagesByCell.entrySet()) {
            String[] location = entry.getKey().split(":", 2);
            int rowIndex = Integer.parseInt(location[0]) - 1;
            int column = Integer.parseInt(location[1]);
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                row = sheet.createRow(rowIndex);
            }
            Cell cell = row.getCell(column, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
            short baseStyleIndex = cell.getCellStyle().getIndex();
            CellStyle warningStyle = warningStyles.computeIfAbsent(baseStyleIndex, ignored -> {
                CellStyle style = workbook.createCellStyle();
                style.cloneStyleFrom(cell.getCellStyle());
                style.setFillForegroundColor(IndexedColors.ROSE.getIndex());
                style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                return style;
            });
            cell.setCellStyle(warningStyle);

            var anchor = workbook.getCreationHelper().createClientAnchor();
            anchor.setCol1(column);
            anchor.setCol2(column + 4);
            anchor.setRow1(rowIndex);
            anchor.setRow2(rowIndex + 5);
            Comment comment = drawing.createCellComment(anchor);
            comment.setAuthor("AuraBoot");
            comment.setString(workbook.getCreationHelper().createRichTextString(
                    String.join("\n", entry.getValue())));
            cell.setCellComment(comment);
        }
    }

    private void appendErrorDetails(XSSFWorkbook workbook,
                                    List<ImportValidationError> errors,
                                    Map<String, String> fieldLabels,
                                    String locale) {
        Sheet details = workbook.createSheet(ERROR_SHEET_NAME);
        var headerFont = workbook.createFont();
        headerFont.setBold(true);
        CellStyle headerStyle = workbook.createCellStyle();
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        Row header = details.createRow(0);
        String[] headers = {
                localized(locale, "import.report.original_row", "Original row"),
                localized(locale, "import.report.field", "Field"),
                localized(locale, "import.report.issue", "Issue")
        };
        for (int column = 0; column < headers.length; column++) {
            Cell cell = header.createCell(column);
            cell.setCellValue(headers[column]);
            cell.setCellStyle(headerStyle);
        }

        List<ImportValidationError> ordered = errors.stream()
                .sorted(Comparator.comparingInt(ImportValidationError::getRowNumber)
                        .thenComparing(error -> error.getFieldCode() == null
                                ? "" : error.getFieldCode()))
                .toList();
        for (int index = 0; index < ordered.size(); index++) {
            ImportValidationError error = ordered.get(index);
            Row row = details.createRow(index + 1);
            row.createCell(0).setCellValue(error.getRowNumber());
            row.createCell(1).setCellValue(error.getFieldCode() == null
                    ? "" : fieldLabels.getOrDefault(error.getFieldCode(), error.getFieldCode()));
            row.createCell(2).setCellValue(localizeErrorMessage(locale, error.getMessage(),
                    error.getFieldCode() == null ? "" : fieldLabels.getOrDefault(
                            error.getFieldCode(), error.getFieldCode())));
        }
        details.setColumnWidth(0, 4000);
        details.setColumnWidth(1, 7000);
        details.setColumnWidth(2, 24000);
        details.createFreezePane(0, 1);
    }

    private Map<String, String> fieldLabels(List<FieldDefinition> fieldDefinitions) {
        Map<String, String> labels = new HashMap<>();
        if (fieldDefinitions == null) {
            return labels;
        }
        for (FieldDefinition field : fieldDefinitions) {
            if (field == null || field.getCode() == null) {
                continue;
            }
            labels.put(field.getCode(), field.getDisplayName() == null
                    || field.getDisplayName().isBlank() ? field.getCode() : field.getDisplayName());
        }
        return labels;
    }

    private int errorCount(List<ImportValidationError> errors) {
        return errors == null ? 0 : errors.size();
    }

    private String localizeErrorMessage(String locale, String message, String fieldLabel) {
        if (message == null || message.isBlank()) {
            return "";
        }
        if ("Required field is missing".equals(message)) {
            return localized(locale, "import.validation.required", message);
        }
        if (message.startsWith("Value cannot be parsed as ")) {
            String type = message.substring("Value cannot be parsed as ".length());
            return localized(locale, "import.validation.type", message).replace("{type}", type);
        }
        if (message.startsWith("Field is not allowed for ")) {
            return localized(locale, "import.validation.field_not_allowed", message);
        }
        if (message.startsWith("Duplicate value on unique field")) {
            return localized(locale, "import.validation.duplicate", message);
        }
        if ("Duplicate column header".equals(message)) {
            return localized(locale, "import.validation.duplicate_header", message);
        }
        if (message.startsWith("Referenced record does not exist or is not accessible")) {
            return localized(locale, "import.validation.reference", message);
        }
        if (message.startsWith("Reference value is ambiguous")) {
            return localized(locale, "import.validation.reference_ambiguous", message);
        }
        if (message.startsWith(ExcelImportService.ROW_WRITE_FAILED_MESSAGE)) {
            return localized(locale, "import.validation.row_write_failed", message);
        }
        if (message.startsWith("Field '") && message.endsWith("' is required")) {
            return localized(locale, "import.validation.named_required", message)
                    .replace("{field}", fieldLabel);
        }
        return message;
    }

    private String localized(String locale, String key, String fallback) {
        String value = i18nService.getValue(normalizeLocale(locale), key);
        if (value == null) {
            value = i18nService.getValue("zh-CN", key);
        }
        return value == null ? fallback : value;
    }

    private String normalizeLocale(String locale) {
        return locale == null || locale.isBlank() ? "zh-CN" : locale;
    }

    private String normalizeMode(String mode) {
        return mode == null || mode.isBlank() ? "insert" : mode.toLowerCase(Locale.ROOT);
    }

    private String reportUrl(String modelCode, String taskId) {
        return REPORT_URL_PREFIX + modelCode + "/error-report/" + taskId;
    }

    private String storageKey(Long tenantId, Long userId, String taskId) {
        return "excel-import/error-reports/" + tenantId + "/" + userId + "/" + taskId + ".xlsx";
    }

    private Long requireContextValue(Long value, String name) {
        if (value == null) {
            throw new BusinessException("Authenticated " + name + " context is required");
        }
        return value;
    }

    private static LocalDateTime utcNow() {
        return LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
    }

    private LocalDateTime retentionCutoff() {
        return LocalDateTime.ofInstant(
                Instant.now().minus(Duration.ofDays(effectiveRetentionDays())), ZoneOffset.UTC);
    }

    private int effectiveRetentionDays() {
        return Math.max(1, retentionDays);
    }
}
