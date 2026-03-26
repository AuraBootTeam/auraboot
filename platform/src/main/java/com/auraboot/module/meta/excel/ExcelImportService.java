package com.auraboot.module.meta.excel;

import com.auraboot.framework.meta.constant.SystemFieldConstants;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.module.meta.excel.entity.ImportJob;
import com.auraboot.module.meta.excel.mapper.ImportJobMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * Reusable Excel import service that parses .xlsx files, validates rows,
 * and inserts data via DynamicDataService.
 *
 * <p>Usage flow:
 * <ol>
 *   <li>Parse Excel stream into raw row maps (header row = column names)</li>
 *   <li>Validate rows (extensible; currently checks for empty rows)</li>
 *   <li>If dryRun, return validation results without persisting</li>
 *   <li>Insert each row via DynamicDataService.create()</li>
 * </ol>
 *
 * @author AuraBoot Team
 * @since 2.5.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExcelImportService {

    /** Number of rows per batch for import insert operations. */
    static final int BATCH_SIZE = 500;
    /** Row count threshold above which import runs asynchronously. */
    static final int ASYNC_THRESHOLD = 1000;

    private final DynamicDataService dynamicDataService;
    private final MetaModelService metaModelService;
    private final ImportJobMapper importJobMapper;

    private final ExecutorService asyncExecutor = Executors.newFixedThreadPool(2);
    private final Map<String, AsyncImportStatus> asyncTasks = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> importEmitters = new ConcurrentHashMap<>();

    /**
     * Status of an async import task.
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class AsyncImportStatus {
        private String taskId;
        private String status; // RUNNING, COMPLETED, FAILED
        private int totalRows;
        private int processedRows;
        private ExcelImportResult result;
    }

    /**
     * Import an Excel file into the specified model.
     *
     * @param modelCode   the target model code
     * @param excelStream input stream of the .xlsx file
     * @param options      import options (skipErrors, dryRun, dateFormat)
     * @return import result with success/error counts
     */
    public ExcelImportResult importExcel(String modelCode, InputStream excelStream, ImportOptions options) {
        if (options == null) {
            options = new ImportOptions();
        }

        // 1. Parse Excel
        List<Map<String, String>> rawRows;
        try {
            rawRows = parseExcel(excelStream, options.getDateFormat());
        } catch (IOException e) {
            log.error("Failed to parse Excel file: {}", e.getMessage());
            return ExcelImportResult.withErrors(
                    List.of(new ImportValidationError(0, null, "Failed to parse Excel: " + e.getMessage())),
                    0);
        }

        if (rawRows.isEmpty()) {
            return ExcelImportResult.success(0, 0, List.of());
        }

        // 2. Resolve header mapping (displayName -> fieldCode)
        List<FieldDefinition> fieldDefs = metaModelService.getModelFields(modelCode);
        Map<String, String> headerMapping = resolveHeaderMapping(
                new ArrayList<>(rawRows.get(0).keySet()), fieldDefs);

        // Remap row keys using resolved mapping
        List<Map<String, String>> mappedRows = new ArrayList<>();
        for (Map<String, String> row : rawRows) {
            Map<String, String> mapped = new LinkedHashMap<>();
            for (var entry : row.entrySet()) {
                String mappedKey = headerMapping.getOrDefault(entry.getKey(), entry.getKey());
                mapped.put(mappedKey, entry.getValue());
            }
            mappedRows.add(mapped);
        }

        // 3. Validate
        List<ImportValidationError> errors = new ArrayList<>(validate(mappedRows));
        if (!errors.isEmpty() && !options.isSkipErrors()) {
            return ExcelImportResult.withErrors(errors, mappedRows.size());
        }

        // 4. DryRun check
        if (options.isDryRun()) {
            return ExcelImportResult.builder()
                    .totalRows(mappedRows.size())
                    .successCount(0)
                    .errorCount(0)
                    .errors(errors)
                    .hasErrors(!errors.isEmpty())
                    .build();
        }

        // 5. Insert/Upsert rows
        int success = 0;
        int errorCount = 0;
        int createdCount = 0;
        int updatedCount = 0;
        String upsertKey = options.getUpsertKey();

        if (upsertKey != null && !upsertKey.isBlank()) {
            // UPSERT mode: per-row lookup + create or update
            for (int i = 0; i < mappedRows.size(); i++) {
                try {
                    Map<String, Object> rowData = new HashMap<>(mappedRows.get(i));
                    Object keyValue = rowData.get(upsertKey);
                    String existingId = findExistingRecordId(modelCode, upsertKey, keyValue);

                    if (existingId != null) {
                        dynamicDataService.update(modelCode, existingId, rowData);
                        updatedCount++;
                    } else {
                        dynamicDataService.create(modelCode, rowData);
                        createdCount++;
                    }
                    success++;
                } catch (Exception e) {
                    errorCount++;
                    errors.add(new ImportValidationError(i + 2, null, e.getMessage()));
                    if (!options.isSkipErrors()) {
                        break;
                    }
                }
            }
        } else {
            // INSERT mode: batch insert
            for (int batchStart = 0; batchStart < mappedRows.size(); batchStart += BATCH_SIZE) {
                int batchEnd = Math.min(batchStart + BATCH_SIZE, mappedRows.size());
                List<Map<String, String>> batch = mappedRows.subList(batchStart, batchEnd);

                try {
                    List<Map<String, Object>> batchData = new ArrayList<>();
                    for (Map<String, String> row : batch) {
                        batchData.add(new HashMap<>(row));
                    }
                    dynamicDataService.batchCreate(modelCode, batchData);
                    success += batch.size();
                    createdCount += batch.size();
                } catch (Exception batchError) {
                    // Batch failed — fall back to per-row for error isolation
                    log.warn("Batch insert failed, falling back to per-row: {}", batchError.getMessage());
                    for (int i = 0; i < batch.size(); i++) {
                        try {
                            dynamicDataService.create(modelCode, new HashMap<>(batch.get(i)));
                            success++;
                            createdCount++;
                        } catch (Exception e) {
                            errorCount++;
                            errors.add(new ImportValidationError(batchStart + i + 2, null, e.getMessage()));
                            if (!options.isSkipErrors()) {
                                return ExcelImportResult.builder()
                                        .totalRows(success + errorCount)
                                        .successCount(success).errorCount(errorCount)
                                        .createdCount(createdCount).updatedCount(updatedCount)
                                        .errors(errors).hasErrors(errorCount > 0).build();
                            }
                        }
                    }
                }
            }
        }

        return ExcelImportResult.builder()
                .totalRows(success + errorCount)
                .successCount(success).errorCount(errorCount)
                .createdCount(createdCount).updatedCount(updatedCount)
                .errors(errors).hasErrors(errorCount > 0).build();
    }

    /**
     * Find the pid of an existing record matching the given field value.
     * Returns null if no match found.
     */
    private String findExistingRecordId(String modelCode, String fieldCode, Object fieldValue) {
        if (fieldValue == null || fieldValue.toString().isBlank()) return null;
        try {
            var condition = com.auraboot.framework.meta.dto.QueryCondition.builder()
                    .fieldName(fieldCode)
                    .operator(com.auraboot.framework.meta.dto.QueryCondition.Operator.EQ)
                    .value(fieldValue)
                    .build();
            var request = com.auraboot.framework.meta.dto.DynamicQueryRequest.builder()
                    .pageNum(1).pageSize(1)
                    .conditions(List.of(condition))
                    .build();
            var result = dynamicDataService.list(modelCode, request);
            if (result != null && result.getRecords() != null && !result.getRecords().isEmpty()) {
                Object pid = result.getRecords().get(0).get("pid");
                return pid != null ? pid.toString() : null;
            }
        } catch (Exception e) {
            log.debug("Upsert lookup failed for {}={}: {}", fieldCode, fieldValue, e.getMessage());
        }
        return null;
    }

    /**
     * Count data rows in an Excel stream (excluding header).
     */
    public int countRows(InputStream stream) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(stream)) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) return 0;
            return Math.max(0, sheet.getLastRowNum()); // row 0 is header
        }
    }

    /**
     * Start an async import and return the task ID.
     * Creates an ImportJob record and emits SSE progress events during processing.
     */
    public String importExcelAsync(String modelCode, InputStream excelStream, ImportOptions options) throws IOException {
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        byte[] bytes = excelStream.readAllBytes();

        // Create import job record
        ImportJob job = new ImportJob();
        job.setModelCode(modelCode);
        job.setStatus(StatusConstants.RUNNING);
        job.setImportMode(options.getUpsertKey() != null ? "upsert" : "insert");
        importJobMapper.insert(job);
        Long jobId = job.getId();

        AsyncImportStatus status = new AsyncImportStatus();
        status.setTaskId(taskId);
        status.setStatus(StatusConstants.RUNNING);
        asyncTasks.put(taskId, status);

        asyncExecutor.submit(() -> {
            try {
                ExcelImportResult result = importExcelWithProgress(modelCode,
                        new java.io.ByteArrayInputStream(bytes), options, taskId, jobId);
                status.setResult(result);
                status.setProcessedRows(result.getTotalRows());
                status.setTotalRows(result.getTotalRows());
                status.setStatus(StatusConstants.COMPLETED);

                // Update import job
                updateImportJob(jobId, "completed", result);

                // Emit final completion event
                emitProgress(taskId, result.getTotalRows(), result.getTotalRows(),
                        result.getErrorCount(), "completed");
            } catch (Exception e) {
                log.error("Async import failed for task {}: {}", taskId, e.getMessage());
                status.setStatus(StatusConstants.FAILED);
                status.setResult(ExcelImportResult.builder()
                        .hasErrors(true)
                        .errors(List.of(new ImportValidationError(0, null, e.getMessage())))
                        .build());

                updateImportJobStatus(jobId, "failed");
                emitProgress(taskId, 0, 0, 0, "failed");
            } finally {
                // Close all SSE emitters for this task
                closeEmitters(taskId);
            }
        });

        log.info("Async import started: taskId={}, model={}, jobId={}", taskId, modelCode, jobId);
        return taskId;
    }

    /**
     * Import with progress reporting for SSE and import job updates.
     */
    private ExcelImportResult importExcelWithProgress(String modelCode, InputStream excelStream,
                                                       ImportOptions options, String taskId, Long jobId) {
        if (options == null) {
            options = new ImportOptions();
        }

        List<Map<String, String>> rawRows;
        try {
            rawRows = parseExcel(excelStream, options.getDateFormat());
        } catch (IOException e) {
            log.error("Failed to parse Excel file: {}", e.getMessage());
            return ExcelImportResult.withErrors(
                    List.of(new ImportValidationError(0, null, "Failed to parse Excel: " + e.getMessage())), 0);
        }

        if (rawRows.isEmpty()) {
            return ExcelImportResult.success(0, 0, List.of());
        }

        // Update job with total rows
        updateImportJobTotalRows(jobId, rawRows.size());

        // Resolve headers
        List<FieldDefinition> fieldDefs = metaModelService.getModelFields(modelCode);
        Map<String, String> headerMapping = resolveHeaderMapping(
                new ArrayList<>(rawRows.get(0).keySet()), fieldDefs);

        List<Map<String, String>> mappedRows = new ArrayList<>();
        for (Map<String, String> row : rawRows) {
            Map<String, String> mapped = new LinkedHashMap<>();
            for (var entry : row.entrySet()) {
                String mappedKey = headerMapping.getOrDefault(entry.getKey(), entry.getKey());
                mapped.put(mappedKey, entry.getValue());
            }
            mappedRows.add(mapped);
        }

        List<ImportValidationError> errors = new ArrayList<>(validate(mappedRows));
        int success = 0;
        int errorCount = 0;
        int createdCount = 0;
        int updatedCount = 0;
        String upsertKey = options.getUpsertKey();

        // INSERT mode with progress
        for (int batchStart = 0; batchStart < mappedRows.size(); batchStart += BATCH_SIZE) {
            int batchEnd = Math.min(batchStart + BATCH_SIZE, mappedRows.size());
            List<Map<String, String>> batch = mappedRows.subList(batchStart, batchEnd);

            try {
                List<Map<String, Object>> batchData = new ArrayList<>();
                for (Map<String, String> row : batch) {
                    batchData.add(new HashMap<>(row));
                }
                dynamicDataService.batchCreate(modelCode, batchData);
                success += batch.size();
                createdCount += batch.size();
            } catch (Exception batchError) {
                log.warn("Batch insert failed, falling back to per-row: {}", batchError.getMessage());
                for (int i = 0; i < batch.size(); i++) {
                    try {
                        dynamicDataService.create(modelCode, new HashMap<>(batch.get(i)));
                        success++;
                        createdCount++;
                    } catch (Exception e) {
                        errorCount++;
                        errors.add(new ImportValidationError(batchStart + i + 2, null, e.getMessage()));
                        if (!options.isSkipErrors()) break;
                    }
                }
            }

            // Emit progress after each batch
            emitProgress(taskId, success + errorCount, mappedRows.size(), errorCount, "running");
            updateImportJobProgress(jobId, success + errorCount, success, errorCount);
        }

        return ExcelImportResult.builder()
                .totalRows(success + errorCount)
                .successCount(success).errorCount(errorCount)
                .createdCount(createdCount).updatedCount(updatedCount)
                .errors(errors).hasErrors(errorCount > 0).build();
    }

    /**
     * Get the status of an async import task.
     */
    public AsyncImportStatus getImportStatus(String taskId) {
        return asyncTasks.get(taskId);
    }

    /**
     * Parse an .xlsx stream into a list of row maps.
     * The first row is treated as the header (column names).
     */
    List<Map<String, String>> parseExcel(InputStream stream, String dateFormat) throws IOException {
        List<Map<String, String>> rows = new ArrayList<>();
        DataFormatter dataFormatter = new DataFormatter();

        try (Workbook workbook = new XSSFWorkbook(stream)) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null || sheet.getPhysicalNumberOfRows() < 2) {
                return rows; // no data rows
            }

            // Read header row
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) return rows;

            List<String> headers = new ArrayList<>();
            for (int c = 0; c < headerRow.getLastCellNum(); c++) {
                Cell cell = headerRow.getCell(c);
                String headerValue = cell != null ? dataFormatter.formatCellValue(cell).trim() : "";
                headers.add(headerValue);
            }

            // Read data rows
            DateTimeFormatter dtf = DateTimeFormatter.ofPattern(dateFormat != null ? dateFormat : "yyyy-MM-dd");
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;

                Map<String, String> rowMap = new LinkedHashMap<>();
                boolean hasData = false;

                for (int c = 0; c < headers.size(); c++) {
                    String header = headers.get(c);
                    if (header.isEmpty()) continue;

                    Cell cell = row.getCell(c);
                    String value = "";
                    if (cell != null) {
                        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                            value = cell.getDateCellValue().toInstant()
                                    .atZone(ZoneId.systemDefault()).toLocalDate().format(dtf);
                        } else {
                            value = dataFormatter.formatCellValue(cell).trim();
                        }
                    }

                    rowMap.put(header, value);
                    if (!value.isEmpty()) {
                        hasData = true;
                    }
                }

                if (hasData) {
                    rows.add(rowMap);
                }
            }
        }

        return rows;
    }

    /**
     * Basic validation: currently just filters fully empty rows.
     * Subclasses or future enhancements can add field-level validation.
     */
    List<ImportValidationError> validate(List<Map<String, String>> rows) {
        List<ImportValidationError> errors = new ArrayList<>();
        // Currently no strict validation beyond empty-row filtering (done in parseExcel).
        // Extension point for field-level validation.
        return errors;
    }

    /**
     * Resolve Excel headers to field codes.
     * Headers that match a field code directly are kept as-is.
     * Headers that match a field's displayName are mapped to the corresponding code.
     * Headers prefixed with "* " (required field marker from template) are stripped before matching.
     * Unmatched headers are kept as-is (will be passed through).
     *
     * @param headers   column headers from the Excel file
     * @param fieldDefs field definitions from the model
     * @return mapping from original header to resolved field code
     */
    /** Auto-generated fields excluded from import templates. */
    private static final Set<String> TEMPLATE_EXCLUDED_FIELDS = SystemFieldConstants.ALL_INFRASTRUCTURE;

    /**
     * Generate an import template XLSX for the given model.
     * <p>
     * Headers use displayName (falling back to field code).
     * Required fields are prefixed with "* " and highlighted with a yellow background.
     * Auto-generated fields (id, pid, timestamps, tenant) are excluded.
     *
     * @param modelCode the target model code
     * @return path to the generated temp file
     */
    public Path generateImportTemplate(String modelCode) throws IOException {
        List<FieldDefinition> allFields = metaModelService.getModelFields(modelCode);

        // Filter out auto-generated, virtual, and primary key fields
        List<FieldDefinition> importableFields = new ArrayList<>();
        for (FieldDefinition fd : allFields) {
            if (TEMPLATE_EXCLUDED_FIELDS.contains(fd.getCode())) continue;
            if (fd.isPrimaryKey()) continue;
            if (fd.isComputedReadonly()) continue;
            importableFields.add(fd);
        }

        Path tempFile = Files.createTempFile("import-template-" + modelCode, ".xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Import");

            // Fonts
            var headerFont = workbook.createFont();
            headerFont.setFontName("Arial Unicode MS");
            headerFont.setFontHeightInPoints((short) 11);
            headerFont.setBold(true);

            // Normal header style (gray background)
            var normalStyle = workbook.createCellStyle();
            normalStyle.setFont(headerFont);
            normalStyle.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.GREY_25_PERCENT.getIndex());
            normalStyle.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);

            // Required header style (yellow background)
            var requiredStyle = workbook.createCellStyle();
            requiredStyle.setFont(headerFont);
            requiredStyle.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.LIGHT_YELLOW.getIndex());
            requiredStyle.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);

            // Write header row
            var headerRow = sheet.createRow(0);
            for (int i = 0; i < importableFields.size(); i++) {
                FieldDefinition fd = importableFields.get(i);
                var cell = headerRow.createCell(i);

                String label = (fd.getDisplayName() != null && !fd.getDisplayName().isBlank())
                        ? fd.getDisplayName() : fd.getCode();
                if (fd.isRequired()) {
                    label = "* " + label;
                }
                cell.setCellValue(label);
                cell.setCellStyle(fd.isRequired() ? requiredStyle : normalStyle);
            }

            // Auto-size columns with minimum width
            for (int i = 0; i < importableFields.size(); i++) {
                sheet.autoSizeColumn(i);
                if (sheet.getColumnWidth(i) < 4000) {
                    sheet.setColumnWidth(i, 4000);
                }
            }

            try (OutputStream os = Files.newOutputStream(tempFile)) {
                workbook.write(os);
            }
        }

        log.info("Generated import template for model {} with {} fields", modelCode, importableFields.size());
        return tempFile;
    }

    // ==================== Chain Import ====================

    /**
     * Chain import: import parent records from Sheet1, then child records from Sheet2
     * with automatic foreign key resolution.
     *
     * @param parentModelCode parent model code (Sheet1)
     * @param childModelCode  child model code (Sheet2)
     * @param parentKeyField  unique field on parent used to match child FK values
     * @param childFkField    field on child that references the parent
     * @param excelStream     multi-sheet .xlsx file
     * @return combined import result
     */
    public ExcelImportResult chainImport(String parentModelCode, String childModelCode,
                                          String parentKeyField, String childFkField,
                                          InputStream excelStream) throws IOException {
        List<ImportValidationError> errors = new ArrayList<>();
        int parentSuccess = 0;
        int childSuccess = 0;
        int parentErrors = 0;
        int childErrors = 0;

        try (Workbook workbook = new XSSFWorkbook(excelStream)) {
            if (workbook.getNumberOfSheets() < 2) {
                return ExcelImportResult.withErrors(
                        List.of(new ImportValidationError(0, null,
                                "Chain import requires at least 2 sheets (parent + child)")), 0);
            }

            // 1. Parse parent rows from Sheet1
            Sheet parentSheet = workbook.getSheetAt(0);
            List<Map<String, String>> parentRawRows = parseSheet(parentSheet, "yyyy-MM-dd");
            if (parentRawRows.isEmpty()) {
                return ExcelImportResult.withErrors(
                        List.of(new ImportValidationError(0, null, "Sheet1 (parent) has no data rows")), 0);
            }

            // Resolve parent headers
            List<FieldDefinition> parentFields = metaModelService.getModelFields(parentModelCode);
            Map<String, String> parentHeaderMapping = resolveHeaderMapping(
                    new ArrayList<>(parentRawRows.get(0).keySet()), parentFields);
            List<Map<String, String>> parentMapped = remapRows(parentRawRows, parentHeaderMapping);

            // 2. Import parent rows and collect generated IDs keyed by parentKeyField value
            Map<String, String> parentKeyToId = new LinkedHashMap<>();
            for (int i = 0; i < parentMapped.size(); i++) {
                try {
                    Map<String, Object> rowData = new HashMap<>(parentMapped.get(i));
                    String keyValue = parentMapped.get(i).get(parentKeyField);
                    Map<String, Object> created = dynamicDataService.create(parentModelCode, rowData);
                    if (keyValue != null && created != null) {
                        Object pid = created.get("pid");
                        if (pid == null) pid = created.get("id");
                        if (pid != null) {
                            parentKeyToId.put(keyValue, pid.toString());
                        }
                    }
                    parentSuccess++;
                } catch (Exception e) {
                    parentErrors++;
                    errors.add(new ImportValidationError(i + 2, null,
                            "[Parent] " + e.getMessage()));
                }
            }

            // 3. Parse child rows from Sheet2
            Sheet childSheet = workbook.getSheetAt(1);
            List<Map<String, String>> childRawRows = parseSheet(childSheet, "yyyy-MM-dd");
            if (childRawRows.isEmpty()) {
                return ExcelImportResult.builder()
                        .totalRows(parentSuccess + parentErrors)
                        .successCount(parentSuccess).errorCount(parentErrors)
                        .createdCount(parentSuccess)
                        .errors(errors).hasErrors(parentErrors > 0).build();
            }

            // Resolve child headers
            List<FieldDefinition> childFields = metaModelService.getModelFields(childModelCode);
            Map<String, String> childHeaderMapping = resolveHeaderMapping(
                    new ArrayList<>(childRawRows.get(0).keySet()), childFields);
            List<Map<String, String>> childMapped = remapRows(childRawRows, childHeaderMapping);

            // 4. Import child rows with resolved FK
            for (int i = 0; i < childMapped.size(); i++) {
                try {
                    Map<String, Object> rowData = new HashMap<>(childMapped.get(i));
                    // Resolve FK: the child's FK field value should match a parent key value
                    Object fkValue = rowData.get(childFkField);
                    if (fkValue != null && parentKeyToId.containsKey(fkValue.toString())) {
                        rowData.put(childFkField, parentKeyToId.get(fkValue.toString()));
                    }
                    dynamicDataService.create(childModelCode, rowData);
                    childSuccess++;
                } catch (Exception e) {
                    childErrors++;
                    errors.add(new ImportValidationError(i + 2, null,
                            "[Child] " + e.getMessage()));
                }
            }
        }

        int totalSuccess = parentSuccess + childSuccess;
        int totalErrors = parentErrors + childErrors;

        return ExcelImportResult.builder()
                .totalRows(totalSuccess + totalErrors)
                .successCount(totalSuccess).errorCount(totalErrors)
                .createdCount(totalSuccess)
                .errors(errors).hasErrors(totalErrors > 0).build();
    }

    /**
     * Parse a specific sheet into row maps.
     */
    private List<Map<String, String>> parseSheet(Sheet sheet, String dateFormat) {
        List<Map<String, String>> rows = new ArrayList<>();
        if (sheet == null || sheet.getPhysicalNumberOfRows() < 2) {
            return rows;
        }

        DataFormatter dataFormatter = new DataFormatter();
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern(dateFormat != null ? dateFormat : "yyyy-MM-dd");

        Row headerRow = sheet.getRow(0);
        if (headerRow == null) return rows;

        List<String> headers = new ArrayList<>();
        for (int c = 0; c < headerRow.getLastCellNum(); c++) {
            Cell cell = headerRow.getCell(c);
            headers.add(cell != null ? dataFormatter.formatCellValue(cell).trim() : "");
        }

        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;

            Map<String, String> rowMap = new LinkedHashMap<>();
            boolean hasData = false;

            for (int c = 0; c < headers.size(); c++) {
                String header = headers.get(c);
                if (header.isEmpty()) continue;

                Cell cell = row.getCell(c);
                String value = "";
                if (cell != null) {
                    if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                        value = cell.getDateCellValue().toInstant()
                                .atZone(ZoneId.systemDefault()).toLocalDate().format(dtf);
                    } else {
                        value = dataFormatter.formatCellValue(cell).trim();
                    }
                }

                rowMap.put(header, value);
                if (!value.isEmpty()) {
                    hasData = true;
                }
            }

            if (hasData) {
                rows.add(rowMap);
            }
        }

        return rows;
    }

    /**
     * Remap row keys using a header mapping.
     */
    private List<Map<String, String>> remapRows(List<Map<String, String>> rows, Map<String, String> headerMapping) {
        List<Map<String, String>> mapped = new ArrayList<>();
        for (Map<String, String> row : rows) {
            Map<String, String> m = new LinkedHashMap<>();
            for (var entry : row.entrySet()) {
                String key = headerMapping.getOrDefault(entry.getKey(), entry.getKey());
                m.put(key, entry.getValue());
            }
            mapped.add(m);
        }
        return mapped;
    }

    // ==================== SSE Progress ====================

    /**
     * Subscribe to SSE progress events for an import task.
     *
     * @param taskId the async import task ID
     * @return SseEmitter that streams progress events
     */
    public SseEmitter subscribeProgress(String taskId) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5-minute timeout

        importEmitters.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        // Cleanup on close/error/timeout
        emitter.onCompletion(() -> removeEmitter(taskId, emitter));
        emitter.onTimeout(() -> removeEmitter(taskId, emitter));
        emitter.onError(e -> removeEmitter(taskId, emitter));

        // Send initial status if task already exists
        AsyncImportStatus status = asyncTasks.get(taskId);
        if (status != null) {
            try {
                emitter.send(SseEmitter.event()
                        .name("progress")
                        .data(Map.of(
                                "taskId", taskId,
                                "status", status.getStatus(),
                                "processed", status.getProcessedRows(),
                                "total", status.getTotalRows(),
                                "errors", 0
                        )));
            } catch (IOException e) {
                log.debug("Failed to send initial SSE event for task {}: {}", taskId, e.getMessage());
                removeEmitter(taskId, emitter);
            }
        }

        return emitter;
    }

    /**
     * Emit a progress event to all SSE subscribers for a task.
     */
    private void emitProgress(String taskId, int processed, int total, int errors, String status) {
        CopyOnWriteArrayList<SseEmitter> emitters = importEmitters.get(taskId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        Map<String, Object> event = Map.of(
                "taskId", taskId,
                "processed", processed,
                "total", total,
                "errors", errors,
                "status", status
        );

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("progress")
                        .data(event));
            } catch (IOException e) {
                log.debug("Failed to emit progress for task {}, removing emitter", taskId);
                removeEmitter(taskId, emitter);
            }
        }
    }

    /**
     * Remove a specific emitter from a task's subscriber list.
     */
    private void removeEmitter(String taskId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitters = importEmitters.get(taskId);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                importEmitters.remove(taskId);
            }
        }
    }

    /**
     * Close all emitters for a task (called when import completes or fails).
     */
    private void closeEmitters(String taskId) {
        CopyOnWriteArrayList<SseEmitter> emitters = importEmitters.remove(taskId);
        if (emitters != null) {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.complete();
                } catch (Exception e) {
                    // ignore
                }
            }
        }
    }

    // ==================== Import Job persistence ====================

    private void updateImportJob(Long jobId, String status, ExcelImportResult result) {
        try {
            ImportJob job = importJobMapper.selectById(jobId);
            if (job != null) {
                job.setStatus(status);
                job.setTotalRows(result.getTotalRows());
                job.setProcessedRows(result.getSuccessCount() + result.getErrorCount());
                job.setSuccessRows(result.getSuccessCount());
                job.setErrorRows(result.getErrorCount());
                // TODO: [timezone-unification] Change to Instant once ImportJob entity fields are migrated.
                LocalDateTime now = LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
                job.setCompletedAt(now);
                job.setUpdatedAt(now);
                importJobMapper.updateById(job);
            }
        } catch (Exception e) {
            log.warn("Failed to update import job {}: {}", jobId, e.getMessage());
        }
    }

    private void updateImportJobStatus(Long jobId, String status) {
        try {
            ImportJob job = importJobMapper.selectById(jobId);
            if (job != null) {
                job.setStatus(status);
                // TODO: [timezone-unification] Change to Instant once ImportJob entity fields are migrated.
                LocalDateTime now = LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
                job.setCompletedAt(now);
                job.setUpdatedAt(now);
                importJobMapper.updateById(job);
            }
        } catch (Exception e) {
            log.warn("Failed to update import job status {}: {}", jobId, e.getMessage());
        }
    }

    private void updateImportJobTotalRows(Long jobId, int totalRows) {
        try {
            ImportJob job = importJobMapper.selectById(jobId);
            if (job != null) {
                job.setTotalRows(totalRows);
                // TODO: [timezone-unification] Change to Instant once ImportJob entity fields are migrated.
                job.setUpdatedAt(LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));
                importJobMapper.updateById(job);
            }
        } catch (Exception e) {
            log.warn("Failed to update import job total rows {}: {}", jobId, e.getMessage());
        }
    }

    private void updateImportJobProgress(Long jobId, int processed, int success, int errors) {
        try {
            ImportJob job = importJobMapper.selectById(jobId);
            if (job != null) {
                job.setProcessedRows(processed);
                job.setSuccessRows(success);
                job.setErrorRows(errors);
                // TODO: [timezone-unification] Change to Instant once ImportJob entity fields are migrated.
                job.setUpdatedAt(LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));
                importJobMapper.updateById(job);
            }
        } catch (Exception e) {
            log.warn("Failed to update import job progress {}: {}", jobId, e.getMessage());
        }
    }

    // ==================== Header Mapping ====================

    static Map<String, String> resolveHeaderMapping(List<String> headers, List<FieldDefinition> fieldDefs) {
        Map<String, String> displayNameToCode = new HashMap<>();
        Set<String> fieldCodes = new HashSet<>();
        for (FieldDefinition fd : fieldDefs) {
            fieldCodes.add(fd.getCode());
            if (fd.getDisplayName() != null && !fd.getDisplayName().isBlank()) {
                displayNameToCode.put(fd.getDisplayName(), fd.getCode());
            }
        }

        Map<String, String> mapping = new LinkedHashMap<>();
        for (String header : headers) {
            if (header == null || header.isBlank()) continue;
            if (fieldCodes.contains(header)) {
                mapping.put(header, header);
            } else if (displayNameToCode.containsKey(header)) {
                mapping.put(header, displayNameToCode.get(header));
            } else {
                // Check with "* " prefix stripped (required field marker from template)
                String stripped = header.startsWith("* ") ? header.substring(2) : header;
                if (fieldCodes.contains(stripped)) {
                    mapping.put(header, stripped);
                } else if (displayNameToCode.containsKey(stripped)) {
                    mapping.put(header, displayNameToCode.get(stripped));
                } else {
                    mapping.put(header, header); // pass through unmatched
                }
            }
        }
        return mapping;
    }
}
