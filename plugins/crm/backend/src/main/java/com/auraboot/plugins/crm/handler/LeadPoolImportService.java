package com.auraboot.plugins.crm.handler;

import com.auraboot.framework.plugin.extension.DataAccessor;
import com.auraboot.framework.plugin.extension.FileAccessor;
import com.auraboot.framework.plugin.extension.RecordShareAccessor;
import com.auraboot.plugins.crm.engine.LeadPoolRules;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Command-owned Excel import for lead pools. It never bypasses pool ownership rules. */
final class LeadPoolImportService {

    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final long MAX_FILE_BYTES = 50L * 1024 * 1024;
    private static final int MAX_ROWS = 5_000;
    private static final Set<String> READABLE_FILE_STATUSES = Set.of("success", "completed", "ready");
    private static final Set<String> OPEN_STATES = Set.of("new", "contacted", "qualified");
    private static final LinkedHashMap<String, String> HEADERS = new LinkedHashMap<>();

    static {
        HEADERS.put("crm_lead_code", "线索编号");
        HEADERS.put("crm_lead_company", "公司名称");
        HEADERS.put("crm_lead_contact_name", "联系人");
        HEADERS.put("crm_lead_contact_phone", "联系电话");
        HEADERS.put("crm_lead_contact_email", "联系邮箱");
        HEADERS.put("crm_lead_source", "线索来源");
        HEADERS.put("crm_lead_industry", "行业");
        HEADERS.put("crm_lead_score", "评分");
        HEADERS.put("crm_lead_status", "状态");
        HEADERS.put("crm_lead_requirement", "需求说明");
    }

    private LeadPoolImportService() {}

    static Map<String, Object> downloadTemplate() {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet data = workbook.createSheet("线索池导入");
            Row header = data.createRow(0);
            int column = 0;
            for (String fieldCode : HEADERS.keySet()) {
                Cell cell = header.createCell(column++);
                cell.setCellValue(fieldCode);
            }
            Row example = data.createRow(1);
            example.createCell(0).setCellValue("LEAD-2026-0001");
            example.createCell(1).setCellValue("示例公司（导入前请删除本行）");
            example.createCell(2).setCellValue("张三");
            example.createCell(3).setCellValue("13900000000");
            example.createCell(4).setCellValue("lead@example.com");
            example.createCell(5).setCellValue("website");
            example.createCell(6).setCellValue("manufacturing");
            example.createCell(7).setCellValue(80);
            example.createCell(8).setCellValue("new");
            example.createCell(9).setCellValue("模板示例行");
            for (int index = 0; index < HEADERS.size(); index++) data.autoSizeColumn(index);

            Sheet guide = workbook.createSheet("填写说明");
            guide.createRow(0).createCell(0).setCellValue("字段代码");
            guide.getRow(0).createCell(1).setCellValue("中文名称");
            guide.getRow(0).createCell(2).setCellValue("规则");
            int rowIndex = 1;
            for (Map.Entry<String, String> entry : HEADERS.entrySet()) {
                Row row = guide.createRow(rowIndex++);
                row.createCell(0).setCellValue(entry.getKey());
                row.createCell(1).setCellValue(entry.getValue());
                row.createCell(2).setCellValue(switch (entry.getKey()) {
                    case "crm_lead_code", "crm_lead_company" -> "必填";
                    case "crm_lead_score" -> "可选：0-100 的整数";
                    case "crm_lead_status" -> "可选：new/contacted/qualified，留空默认 new";
                    default -> "可选";
                });
            }
            guide.autoSizeColumn(0);
            guide.autoSizeColumn(1);
            guide.autoSizeColumn(2);
            workbook.write(output);
            return Map.of(
                    "fileName", "crm-lead-pool-import-template.xlsx",
                    "contentType", XLSX_CONTENT_TYPE,
                    "contentBase64", Base64.getEncoder().encodeToString(output.toByteArray()));
        } catch (Exception error) {
            throw new IllegalStateException("Lead-pool import template could not be generated", error);
        }
    }

    static Map<String, Object> precheck(DataAccessor db, FileAccessor files, String poolId,
                                        String actor, String uploadOwnerUserId, Map<String, Object> payload) {
        requirePoolMember(db, poolId, actor);
        ImportPlan plan = buildPlan(db, files, poolId, actor, uploadOwnerUserId, payload);
        return result(plan, 0, 0);
    }

    static Map<String, Object> importLeads(DataAccessor db, FileAccessor files,
                                               RecordShareAccessor shares, long tenantId,
                                               String poolId, String actor, String uploadOwnerUserId,
                                               Map<String, Object> payload) {
        requirePoolMember(db, poolId, actor);
        ImportPlan plan = buildPlan(db, files, poolId, actor, uploadOwnerUserId, payload);
        boolean skipErrors = booleanValue(payload == null ? null : payload.get("skipErrors"));
        if (!plan.failures().isEmpty() && !skipErrors) return result(plan, 0, 0);

        int created = 0;
        int updated = 0;
        for (ImportRow row : plan.validRows()) {
            if (plan.importType() == ImportType.ADD) {
                Map<String, Object> lead = new HashMap<>(row.values());
                lead.put("crm_lead_assigned_to", actor);
                lead.put("crm_lead_pool_state", "owned");
                Map<String, Object> createdLead = db.create("crm_lead_common", lead);
                LeadPoolCommandHandler.moveToPool(db, required(createdLead.get("pid"),
                                "Created lead has no public pid"), poolId, actor,
                        "Imported into lead pool", "imported_to_pool", Instant.now(), shares, tenantId);
                created++;
            } else {
                Map<String, Object> existing = row.existing();
                String leadId = required(existing.get("pid"), "Existing lead has no public pid");
                String currentPoolId = text(existing.get("crm_lead_last_pool_id"));
                db.update("crm_lead_common", leadId, row.values());
                if ("in_pool".equals(text(existing.get("crm_lead_pool_state")))) {
                    if (!poolId.equals(currentPoolId)) {
                        throw new IllegalStateException("Lead " + row.code()
                                + " belongs to a different lead pool");
                    }
                    syncExistingPoolSnapshot(db, leadId, row.values());
                } else {
                    LeadPoolCommandHandler.moveToPool(db, leadId, poolId, actor,
                            "Updated and imported into lead pool", "imported_to_pool", Instant.now(),
                            shares, tenantId);
                }
                updated++;
            }
        }
        return result(plan, created, updated);
    }

    private static ImportPlan buildPlan(DataAccessor db, FileAccessor files, String poolId,
                                        String actor, String uploadOwnerUserId, Map<String, Object> payload) {
        String fileId = required(payload == null ? null : payload.get("importFileId"),
                "importFileId is required");
        ImportType importType = ImportType.parse(payload == null ? null : payload.get("importType"));
        verifyFile(files, fileId, uploadOwnerUserId);
        try (InputStream input = files.open(fileId); Workbook workbook = new XSSFWorkbook(input)) {
            if (workbook.getNumberOfSheets() == 0) throw new IllegalArgumentException("Workbook has no sheets");
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet.getPhysicalNumberOfRows() == 0) throw new IllegalArgumentException("Workbook is empty");
            Map<Integer, String> columns = resolveColumns(sheet.getRow(sheet.getFirstRowNum()));
            ArrayList<ImportRow> validRows = new ArrayList<>();
            ArrayList<Map<String, Object>> failures = new ArrayList<>();
            LinkedHashSet<String> seenCodes = new LinkedHashSet<>();
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            int totalRows = 0;
            for (int index = sheet.getFirstRowNum() + 1; index <= sheet.getLastRowNum(); index++) {
                Row source = sheet.getRow(index);
                if (source == null || isBlank(source, formatter)) continue;
                totalRows++;
                if (totalRows > MAX_ROWS) throw new IllegalArgumentException("Workbook exceeds 5000 data rows");
                Map<String, Object> values = readRow(source, columns, formatter);
                int displayRow = index + 1;
                List<String> reasons = validateValues(values);
                String code = text(values.get("crm_lead_code"));
                if (code != null && !seenCodes.add(code)) reasons.add("Duplicate lead code in workbook: " + code);
                List<Map<String, Object>> matches = code == null ? List.of()
                        : safeQuery(db, "crm_lead_common", Map.of("crm_lead_code", code));
                Map<String, Object> existing = matches.isEmpty() ? null : matches.getFirst();
                if (matches.size() > 1) reasons.add("Lead code is not unique in the system: " + code);
                if (importType == ImportType.ADD && existing != null) reasons.add("Lead code already exists: " + code);
                if (importType == ImportType.UPDATE && existing == null) reasons.add("Lead code does not exist: " + code);
                if (importType == ImportType.UPDATE && existing != null) {
                    String state = text(existing.get("crm_lead_pool_state"));
                    String existingPool = text(existing.get("crm_lead_last_pool_id"));
                    if ("in_pool".equals(state) && !poolId.equals(existingPool)) {
                        reasons.add("Lead belongs to a different lead pool: " + code);
                    } else if (!"in_pool".equals(state)
                            && !actor.equals(text(existing.get("crm_lead_assigned_to")))
                            && !isPoolAdministrator(db, poolId, actor)) {
                        reasons.add("Current user is not the lead owner or pool administrator: " + code);
                    }
                }
                if (reasons.isEmpty()) validRows.add(new ImportRow(displayRow, code, values, existing));
                else failures.add(Map.of("row", displayRow, "reason", String.join("; ", reasons)));
            }
            return new ImportPlan(importType, totalRows, List.copyOf(validRows), List.copyOf(failures));
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("Uploaded file is not a readable .xlsx workbook", error);
        }
    }

    private static void verifyFile(FileAccessor files, String fileId, String uploadOwnerUserId) {
        FileAccessor.FileMetadata metadata = files.describe(fileId);
        if (metadata == null || !fileId.equals(text(metadata.fileId()))) {
            throw new IllegalArgumentException("Uploaded file metadata is unavailable or mismatched");
        }
        if (!uploadOwnerUserId.equals(text(metadata.ownerUserId()))) {
            throw new SecurityException("Uploaded file owner does not match the authenticated actor");
        }
        String status = text(metadata.status());
        if (status == null || !READABLE_FILE_STATUSES.contains(status.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Uploaded file is not finalized");
        }
        if (metadata.size() < 0 || metadata.size() > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("Uploaded file exceeds the 50 MB limit");
        }
        String name = text(metadata.originalName());
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new IllegalArgumentException("Only .xlsx files are accepted");
        }
    }

    private static Map<Integer, String> resolveColumns(Row header) {
        if (header == null) throw new IllegalArgumentException("Workbook header row is missing");
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        LinkedHashMap<Integer, String> columns = new LinkedHashMap<>();
        Set<String> resolved = new LinkedHashSet<>();
        for (Cell cell : header) {
            String raw = formatter.formatCellValue(cell).trim();
            String field = HEADERS.containsKey(raw) ? raw : HEADERS.entrySet().stream()
                    .filter(entry -> entry.getValue().equals(raw)).map(Map.Entry::getKey).findFirst().orElse(null);
            if (field != null && resolved.add(field)) columns.put(cell.getColumnIndex(), field);
        }
        if (!resolved.contains("crm_lead_code") || !resolved.contains("crm_lead_company")) {
            throw new IllegalArgumentException("Workbook must include crm_lead_code and crm_lead_company columns");
        }
        return columns;
    }

    private static Map<String, Object> readRow(Row source, Map<Integer, String> columns, DataFormatter formatter) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> column : columns.entrySet()) {
            String value = formatter.formatCellValue(source.getCell(column.getKey(), Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)).trim();
            if (!value.isBlank()) values.put(column.getValue(), value);
        }
        values.putIfAbsent("crm_lead_status", "new");
        return values;
    }

    private static List<String> validateValues(Map<String, Object> values) {
        ArrayList<String> reasons = new ArrayList<>();
        validateRequired(values, "crm_lead_code", "Lead code", 100, reasons);
        validateRequired(values, "crm_lead_company", "Lead company", 200, reasons);
        validateLength(values, "crm_lead_contact_name", "Contact name", 100, reasons);
        validateLength(values, "crm_lead_contact_phone", "Contact phone", 50, reasons);
        validateLength(values, "crm_lead_contact_email", "Contact email", 200, reasons);
        validateLength(values, "crm_lead_source", "Lead source", 100, reasons);
        validateLength(values, "crm_lead_industry", "Industry", 100, reasons);
        validateLength(values, "crm_lead_requirement", "Requirement", 2_000, reasons);
        String score = text(values.get("crm_lead_score"));
        if (score != null) {
            try {
                int parsed = Integer.parseInt(score);
                if (parsed < 0 || parsed > 100) reasons.add("Lead score must be between 0 and 100");
                else values.put("crm_lead_score", parsed);
            } catch (NumberFormatException error) {
                reasons.add("Lead score must be an integer between 0 and 100");
            }
        }
        String status = text(values.get("crm_lead_status"));
        if (status == null || !OPEN_STATES.contains(status.toLowerCase(Locale.ROOT))) {
            reasons.add("Lead status must be new, contacted, or qualified before entering a lead pool");
        } else {
            values.put("crm_lead_status", status.toLowerCase(Locale.ROOT));
        }
        return reasons;
    }

    private static void validateRequired(Map<String, Object> values, String key, String label,
                                         int maxLength, List<String> reasons) {
        String value = text(values.get(key));
        if (value == null) reasons.add(label + " is required");
        else if (value.length() > maxLength) reasons.add(label + " exceeds " + maxLength + " characters");
    }

    private static void validateLength(Map<String, Object> values, String key, String label,
                                       int maxLength, List<String> reasons) {
        String value = text(values.get(key));
        if (value != null && value.length() > maxLength) reasons.add(label + " exceeds " + maxLength + " characters");
    }

    private static boolean isBlank(Row row, DataFormatter formatter) {
        for (Cell cell : row) if (!formatter.formatCellValue(cell).trim().isEmpty()) return false;
        return true;
    }

    private static void syncExistingPoolSnapshot(DataAccessor db, String leadId, Map<String, Object> values) {
        List<Map<String, Object>> items = safeQuery(db, "crm_lead_pool_item_common",
                Map.of("crm_lpi_lead_key", leadId));
        if (items.isEmpty()) throw new IllegalStateException("Lead-pool projection is missing for " + leadId);
        HashMap<String, Object> patch = new HashMap<>();
        copyIfPresent(values, "crm_lead_code", patch, "crm_lpi_lead_code");
        copyIfPresent(values, "crm_lead_company", patch, "crm_lpi_company");
        copyIfPresent(values, "crm_lead_contact_name", patch, "crm_lpi_contact_name");
        copyIfPresent(values, "crm_lead_source", patch, "crm_lpi_source");
        copyIfPresent(values, "crm_lead_contact_phone", patch, "crm_lpi_contact_phone");
        copyIfPresent(values, "crm_lead_score", patch, "crm_lpi_score");
        if (!patch.isEmpty()) db.update("crm_lead_pool_item_common",
                required(items.getFirst().get("pid"), "Pool item has no public pid"), patch);
    }

    private static void copyIfPresent(Map<String, Object> source, String sourceKey,
                                      Map<String, Object> target, String targetKey) {
        if (source.containsKey(sourceKey)) target.put(targetKey, source.get(sourceKey));
    }

    private static Map<String, Object> result(ImportPlan plan, int created, int updated) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("importType", plan.importType().name());
        result.put("totalRows", plan.totalRows());
        result.put("validRows", plan.validRows().size());
        result.put("failedRows", plan.failures().size());
        result.put("skippedRows", plan.failures().size());
        result.put("createdRows", created);
        result.put("updatedRows", updated);
        result.put("importedRows", created + updated);
        result.put("failures", plan.failures());
        return result;
    }

    private static Map<String, Object> requirePoolMember(DataAccessor db, String poolId, String actor) {
        Map<String, Object> pool = db.getById("crm_lead_pool_common", poolId);
        if (pool == null) throw new IllegalArgumentException("Lead pool not found: " + poolId);
        if (!"enabled".equals(text(pool.get("crm_lp_status")))) {
            throw new IllegalStateException("Lead pool is disabled: " + poolId);
        }
        if (!LeadPoolRules.isMember(pool.get("crm_lp_member_user_ids"), pool.get("crm_lp_admin_user_ids"), actor)) {
            throw new SecurityException("Current user is not a member of lead pool " + poolId);
        }
        return pool;
    }

    private static boolean isPoolAdministrator(DataAccessor db, String poolId, String actor) {
        Map<String, Object> pool = db.getById("crm_lead_pool_common", poolId);
        return pool != null && LeadPoolRules.isAdministrator(pool.get("crm_lp_admin_user_ids"), actor);
    }

    private static List<Map<String, Object>> safeQuery(DataAccessor db, String model, Map<String, Object> filters) {
        List<Map<String, Object>> result = db.query(model, filters);
        return result == null ? List.of() : result;
    }

    private static boolean booleanValue(Object value) {
        return value instanceof Boolean bool ? bool : value != null && Boolean.parseBoolean(value.toString());
    }

    private static String required(Object value, String message) {
        String text = text(value);
        if (text == null) throw new IllegalArgumentException(message);
        return text;
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private enum ImportType {
        ADD, UPDATE;

        static ImportType parse(Object value) {
            String raw = text(value);
            if (raw == null) return ADD;
            try {
                return ImportType.valueOf(raw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("importType must be ADD or UPDATE");
            }
        }
    }

    private record ImportRow(int row, String code, Map<String, Object> values, Map<String, Object> existing) {}
    private record ImportPlan(ImportType importType, int totalRows,
                              List<ImportRow> validRows, List<Map<String, Object>> failures) {}
}
