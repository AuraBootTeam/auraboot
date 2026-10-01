package com.auraboot.module.meta.excel;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.TypeSystemManager;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.module.meta.excel.entity.ImportJob;
import com.auraboot.module.meta.excel.mapper.ImportJobMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Executes one aggregate command per document group from a standard two-sheet workbook. */
@Service
@RequiredArgsConstructor
public class DocumentImportService {

    private final DocumentImportProfileResolver profileResolver;
    private final MetaModelService metaModelService;
    private final CommandExecutor commandExecutor;
    private final TypeSystemManager typeSystemManager;
    private final ObjectMapper objectMapper;
    private final ImportJobMapper importJobMapper;

    public Path generateTemplate(String modelCode) throws IOException {
        DocumentImportProfile profile = profileResolver.requireEnabled(modelCode);
        Path file = Files.createTempFile("document-import-", ".xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            writeHeader(workbook.createSheet(profile.getHeaderSheet()), profile.getHeaderFields());
            writeHeader(workbook.createSheet(profile.getLineSheet()), profile.getLineFields());
            try (OutputStream output = Files.newOutputStream(file)) {
                workbook.write(output);
            }
        }
        return file;
    }

    public ExcelImportResult importWorkbook(String modelCode, InputStream input,
                                            boolean dryRun, boolean skipErrors) throws IOException {
        return importWorkbook(modelCode, input, dryRun, skipErrors, null);
    }

    public ExcelImportResult importWorkbook(String modelCode, InputStream input,
                                            boolean dryRun, boolean skipErrors,
                                            String fileName) throws IOException {
        DocumentImportProfile profile = profileResolver.requireEnabled(modelCode);
        if (dryRun) return execute(profile, input, true, skipErrors);

        ImportJob job = createJob(profile, modelCode, fileName);
        ExcelImportResult result;
        try {
            result = execute(profile, input, false, skipErrors);
        } catch (IOException | RuntimeException failure) {
            ExcelImportResult failed = ExcelImportResult.builder()
                    .totalRows(1)
                    .successCount(0)
                    .errorCount(1)
                    .errors(List.of(new ImportValidationError(0, null,
                            "Document import failed; contact an administrator with the import task id")))
                    .hasErrors(true)
                    .taskId(job.getPid())
                    .build();
            try {
                completeJob(job, failed, "failed");
            } catch (RuntimeException terminalPersistenceFailure) {
                failure.addSuppressed(terminalPersistenceFailure);
            }
            throw failure;
        }
        result.setTaskId(job.getPid());
        completeJob(job, result, "completed");
        return result;
    }

    private ExcelImportResult execute(DocumentImportProfile profile, InputStream input,
                                      boolean dryRun, boolean skipErrors) throws IOException {
        List<ImportValidationError> errors = new ArrayList<>();
        List<ParsedRow> headers;
        List<ParsedRow> lines;
        try (XSSFWorkbook workbook = new XSSFWorkbook(input)) {
            headers = parseSheet(workbook.getSheet(profile.getHeaderSheet()),
                    profile.getHeaderSheet(), profile.getHeaderFields());
            lines = parseSheet(workbook.getSheet(profile.getLineSheet()),
                    profile.getLineSheet(), profile.getLineFields());
        }

        Map<String, ParsedRow> headerByGroup = new LinkedHashMap<>();
        for (ParsedRow header : headers) {
            String group = requiredGroup(header, profile.getGroupBy(), profile.getHeaderSheet(), errors);
            if (group != null && headerByGroup.putIfAbsent(group, header) != null) {
                errors.add(new ImportValidationError(header.rowNumber(), profile.getGroupBy(),
                        "Duplicate document group: " + group));
            }
        }
        Map<String, List<ParsedRow>> linesByGroup = new LinkedHashMap<>();
        for (ParsedRow line : lines) {
            String group = requiredGroup(line, profile.getLineGroupField(), profile.getLineSheet(), errors);
            if (group == null) continue;
            if (!headerByGroup.containsKey(group)) {
                errors.add(new ImportValidationError(line.rowNumber(), profile.getLineGroupField(),
                        "Line references an unknown document group: " + group));
                continue;
            }
            linesByGroup.computeIfAbsent(group, ignored -> new ArrayList<>()).add(line);
        }
        if (profile.isRequireLines()) {
            headerByGroup.forEach((group, header) -> {
                if (!linesByGroup.containsKey(group)) {
                    errors.add(new ImportValidationError(header.rowNumber(), profile.getGroupBy(),
                            "Document group has no lines: " + group));
                }
            });
        }
        if (!errors.isEmpty()) return ExcelImportResult.withErrors(errors, headerByGroup.size());
        if (dryRun) {
            return ExcelImportResult.builder()
                    .totalRows(headerByGroup.size())
                    .successCount(0)
                    .errorCount(0)
                    .errors(List.of())
                    .hasErrors(false)
                    .build();
        }

        int success = 0;
        for (Map.Entry<String, ParsedRow> entry : headerByGroup.entrySet()) {
            String group = entry.getKey();
            ParsedRow header = entry.getValue();
            try {
                Map<String, Object> payload = convert(profile.getModelCode(), header.values());
                List<Map<String, Object>> convertedLines = new ArrayList<>();
                for (ParsedRow line : linesByGroup.getOrDefault(group, List.of())) {
                    convertedLines.add(convert(profile.getLineModelCode(), line.values()));
                }
                payload.put(profile.getLinesPayloadField(), convertedLines);
                CommandExecuteRequest request = new CommandExecuteRequest();
                request.setOperationType("create");
                request.setPayload(payload);
                request.setClientRequestId(clientRequestId(profile, payload));
                request.setAuditContext(Map.of(
                        "source", "excel_document_import",
                        "profileCode", profile.getCode(),
                        "groupKey", group));
                commandExecutor.execute(profile.getCommand(), request);
                success++;
            } catch (Exception failure) {
                errors.add(new ImportValidationError(header.rowNumber(), profile.getGroupBy(),
                        safeMessage(failure)));
                if (!skipErrors) break;
            }
        }
        return ExcelImportResult.builder()
                .totalRows(headerByGroup.size())
                .successCount(success)
                .errorCount(errors.size())
                .createdCount(success)
                .updatedCount(0)
                .errors(errors)
                .hasErrors(!errors.isEmpty())
                .build();
    }

    private ImportJob createJob(DocumentImportProfile profile, String modelCode, String fileName) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        if (tenantId == null || userId == null) {
            throw new BusinessException("Authenticated document import owner is required");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        ImportJob job = new ImportJob();
        job.setPid(UniqueIdGenerator.generate());
        job.setTenantId(tenantId);
        job.setCreatedBy(userId);
        job.setModelCode(modelCode);
        job.setFileName(fileName);
        job.setImportMode("DOCUMENT:" + profile.getCode());
        job.setStatus("running");
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        job.setDeletedFlag(false);
        if (importJobMapper.insert(job) != 1 || job.getId() == null) {
            throw new BusinessException("Failed to persist document import task before execution");
        }
        return job;
    }

    private void completeJob(ImportJob job, ExcelImportResult result, String status) {
        job.setStatus(status);
        job.setTotalRows(result.getTotalRows());
        job.setProcessedRows(result.getTotalRows());
        job.setSuccessRows(result.getSuccessCount());
        job.setErrorRows(Math.max(0, result.getTotalRows() - result.getSuccessCount()));
        try {
            job.setErrorDetails(objectMapper.writeValueAsString(
                    result.getErrors() == null ? List.of() : result.getErrors()));
        } catch (IOException serializationFailure) {
            job.setErrorDetails("[]");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        job.setUpdatedAt(now);
        job.setCompletedAt(now);
        if (importJobMapper.updateById(job) != 1) {
            throw new BusinessException("Failed to persist terminal document import task");
        }
    }

    private Map<String, Object> convert(String modelCode, Map<String, String> values) {
        Map<String, FieldDefinition> fields = new LinkedHashMap<>();
        List<FieldDefinition> definitions = metaModelService.getModelFields(modelCode);
        if (definitions != null) {
            definitions.forEach(field -> fields.put(field.getCode(), field));
        }
        Map<String, Object> converted = new LinkedHashMap<>();
        values.forEach((code, value) -> {
            if (value == null || value.isBlank()) return;
            FieldDefinition field = fields.get(code);
            converted.put(code, field == null || field.getDataType() == null || field.getDataType().isBlank()
                    ? value : typeSystemManager.convertValue(value, field));
        });
        return converted;
    }

    private List<ParsedRow> parseSheet(Sheet sheet, String sheetName, List<String> allowedFields) {
        if (sheet == null) throw new BusinessException("Required worksheet is missing: " + sheetName);
        Row header = sheet.getRow(0);
        if (header == null) throw new BusinessException("Worksheet has no header row: " + sheetName);
        DataFormatter formatter = new DataFormatter();
        List<String> fields = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int cell = 0; cell < header.getLastCellNum(); cell++) {
            String field = formatter.formatCellValue(header.getCell(cell)).trim();
            if (field.isBlank() || !allowedFields.contains(field) || !seen.add(field)) {
                throw new BusinessException("Unexpected or duplicate column in " + sheetName + ": " + field);
            }
            fields.add(field);
        }
        if (!seen.containsAll(allowedFields)) {
            Set<String> missing = new LinkedHashSet<>(allowedFields);
            missing.removeAll(seen);
            throw new BusinessException("Missing columns in " + sheetName + ": " + String.join(",", missing));
        }
        List<ParsedRow> rows = new ArrayList<>();
        for (int index = 1; index <= sheet.getLastRowNum(); index++) {
            Row row = sheet.getRow(index);
            if (row == null) continue;
            Map<String, String> values = new LinkedHashMap<>();
            boolean nonEmpty = false;
            for (int cell = 0; cell < fields.size(); cell++) {
                String value = formatter.formatCellValue(row.getCell(cell)).trim();
                values.put(fields.get(cell), value);
                nonEmpty |= !value.isBlank();
            }
            if (nonEmpty) rows.add(new ParsedRow(index + 1, values));
        }
        return rows;
    }

    private String clientRequestId(DocumentImportProfile profile, Map<String, Object> payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String hash = HexFormat.of().formatHex(digest.digest(objectMapper.writeValueAsBytes(payload)));
            return "excel-document:" + MetaContext.getCurrentTenantId() + ":" + profile.getCode()
                    + ":" + hash;
        } catch (NoSuchAlgorithmException | IOException failure) {
            throw new IllegalStateException("Cannot create document import idempotency key", failure);
        }
    }

    private static String requiredGroup(ParsedRow row, String field, String sheet,
                                        List<ImportValidationError> errors) {
        String value = row.values().get(field);
        if (value == null || value.isBlank()) {
            errors.add(new ImportValidationError(row.rowNumber(), field,
                    "Grouping value is required in worksheet " + sheet));
            return null;
        }
        return value;
    }

    private static String safeMessage(Exception failure) {
        if (failure instanceof BusinessException && failure.getMessage() != null
                && !failure.getMessage().isBlank()) return failure.getMessage();
        return "Document command failed; contact an administrator with the import task id";
    }

    private static void writeHeader(Sheet sheet, List<String> fields) {
        Row header = sheet.createRow(0);
        for (int index = 0; index < fields.size(); index++) {
            header.createCell(index).setCellValue(fields.get(index));
            sheet.setColumnWidth(index, 5000);
        }
        sheet.createFreezePane(0, 1);
    }

    private record ParsedRow(int rowNumber, Map<String, String> values) {}
}
