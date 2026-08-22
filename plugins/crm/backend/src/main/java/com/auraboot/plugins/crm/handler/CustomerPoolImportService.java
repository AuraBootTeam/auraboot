package com.auraboot.plugins.crm.handler;

import com.auraboot.framework.plugin.extension.DataAccessor;
import com.auraboot.framework.plugin.extension.FileAccessor;
import com.auraboot.framework.plugin.extension.RecordShareAccessor;
import com.auraboot.plugins.crm.engine.CustomerPoolRules;
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

/** Command-owned Excel import for customer pools. It never bypasses pool ownership rules. */
final class CustomerPoolImportService {

    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final long MAX_FILE_BYTES = 50L * 1024 * 1024;
    private static final int MAX_ROWS = 5_000;
    private static final Set<String> READABLE_FILE_STATUSES = Set.of("success", "completed", "ready");
    private static final Set<String> RATINGS = Set.of("A", "B", "C", "D");
    private static final LinkedHashMap<String, String> HEADERS = new LinkedHashMap<>();

    static {
        HEADERS.put("crm_acc_code", "客户编号");
        HEADERS.put("crm_acc_name", "客户名称");
        HEADERS.put("crm_acc_industry", "行业");
        HEADERS.put("crm_acc_website", "网站");
        HEADERS.put("crm_acc_phone", "电话");
        HEADERS.put("crm_acc_address", "地址");
        HEADERS.put("crm_acc_rating", "评级");
        HEADERS.put("crm_acc_status", "状态");
        HEADERS.put("crm_acc_remark", "备注");
    }

    private CustomerPoolImportService() {}

    static Map<String, Object> downloadTemplate() {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet data = workbook.createSheet("客户公海导入");
            Row header = data.createRow(0);
            int column = 0;
            for (String fieldCode : HEADERS.keySet()) {
                Cell cell = header.createCell(column++);
                cell.setCellValue(fieldCode);
            }
            Row example = data.createRow(1);
            example.createCell(0).setCellValue("CUST-2026-0001");
            example.createCell(1).setCellValue("示例客户（导入前请删除本行）");
            example.createCell(2).setCellValue("manufacturing");
            example.createCell(3).setCellValue("https://example.com");
            example.createCell(4).setCellValue("0755-12345678");
            example.createCell(5).setCellValue("深圳市南山区");
            example.createCell(6).setCellValue("A");
            example.createCell(7).setCellValue("active");
            example.createCell(8).setCellValue("模板示例行");
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
                    case "crm_acc_code", "crm_acc_name" -> "必填";
                    case "crm_acc_rating" -> "可选：A/B/C/D";
                    case "crm_acc_status" -> "可选；客户公海仅接受 active，留空默认 active";
                    default -> "可选";
                });
            }
            guide.autoSizeColumn(0);
            guide.autoSizeColumn(1);
            guide.autoSizeColumn(2);
            workbook.write(output);
            return Map.of(
                    "fileName", "crm-customer-pool-import-template.xlsx",
                    "contentType", XLSX_CONTENT_TYPE,
                    "contentBase64", Base64.getEncoder().encodeToString(output.toByteArray()));
        } catch (Exception error) {
            throw new IllegalStateException("Customer-pool import template could not be generated", error);
        }
    }

    static Map<String, Object> precheck(DataAccessor db, FileAccessor files, String poolId,
                                        String actor, String uploadOwnerUserId, Map<String, Object> payload) {
        requirePoolMember(db, poolId, actor);
        ImportPlan plan = buildPlan(db, files, poolId, actor, uploadOwnerUserId, payload);
        return result(plan, 0, 0);
    }

    static Map<String, Object> importCustomers(DataAccessor db, FileAccessor files,
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
                Map<String, Object> customer = new HashMap<>(row.values());
                customer.put("crm_acc_owner", actor);
                customer.put("crm_acc_pool_state", "owned");
                Map<String, Object> createdCustomer = db.create("crm_account_common", customer);
                CustomerPoolCommandHandler.moveToPool(db, required(createdCustomer.get("pid"),
                                "Created customer has no public pid"), poolId, actor,
                        "Imported into customer pool", "imported_to_pool", Instant.now(), shares, tenantId);
                created++;
            } else {
                Map<String, Object> existing = row.existing();
                String customerId = required(existing.get("pid"), "Existing customer has no public pid");
                String currentPoolId = text(existing.get("crm_acc_last_pool_id"));
                db.update("crm_account_common", customerId, row.values());
                if ("in_pool".equals(text(existing.get("crm_acc_pool_state")))) {
                    if (!poolId.equals(currentPoolId)) {
                        throw new IllegalStateException("Customer " + row.code()
                                + " belongs to a different customer pool");
                    }
                    syncExistingPoolSnapshot(db, customerId, row.values());
                } else {
                    CustomerPoolCommandHandler.moveToPool(db, customerId, poolId, actor,
                            "Updated and imported into customer pool", "imported_to_pool", Instant.now(),
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
                String code = text(values.get("crm_acc_code"));
                if (code != null && !seenCodes.add(code)) reasons.add("Duplicate customer code in workbook: " + code);
                List<Map<String, Object>> matches = code == null ? List.of()
                        : safeQuery(db, "crm_account_common", Map.of("crm_acc_code", code));
                Map<String, Object> existing = matches.isEmpty() ? null : matches.getFirst();
                if (matches.size() > 1) reasons.add("Customer code is not unique in the system: " + code);
                if (importType == ImportType.ADD && existing != null) reasons.add("Customer code already exists: " + code);
                if (importType == ImportType.UPDATE && existing == null) reasons.add("Customer code does not exist: " + code);
                if (importType == ImportType.UPDATE && existing != null) {
                    String state = text(existing.get("crm_acc_pool_state"));
                    String existingPool = text(existing.get("crm_acc_last_pool_id"));
                    if ("in_pool".equals(state) && !poolId.equals(existingPool)) {
                        reasons.add("Customer belongs to a different customer pool: " + code);
                    } else if (!"in_pool".equals(state)
                            && !actor.equals(text(existing.get("crm_acc_owner")))
                            && !isPoolAdministrator(db, poolId, actor)) {
                        reasons.add("Current user is not the customer owner or pool administrator: " + code);
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
        if (!resolved.contains("crm_acc_code") || !resolved.contains("crm_acc_name")) {
            throw new IllegalArgumentException("Workbook must include crm_acc_code and crm_acc_name columns");
        }
        return columns;
    }

    private static Map<String, Object> readRow(Row source, Map<Integer, String> columns, DataFormatter formatter) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> column : columns.entrySet()) {
            String value = formatter.formatCellValue(source.getCell(column.getKey(), Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)).trim();
            if (!value.isBlank()) values.put(column.getValue(), value);
        }
        values.putIfAbsent("crm_acc_status", "active");
        return values;
    }

    private static List<String> validateValues(Map<String, Object> values) {
        ArrayList<String> reasons = new ArrayList<>();
        validateRequired(values, "crm_acc_code", "Customer code", 100, reasons);
        validateRequired(values, "crm_acc_name", "Customer name", 200, reasons);
        validateLength(values, "crm_acc_industry", "Industry", 100, reasons);
        validateLength(values, "crm_acc_website", "Website", 500, reasons);
        validateLength(values, "crm_acc_phone", "Phone", 50, reasons);
        validateLength(values, "crm_acc_address", "Address", 500, reasons);
        validateLength(values, "crm_acc_remark", "Remark", 2_000, reasons);
        String rating = text(values.get("crm_acc_rating"));
        if (rating != null && !RATINGS.contains(rating.toUpperCase(Locale.ROOT))) {
            reasons.add("Rating must be A, B, C or D");
        } else if (rating != null) {
            values.put("crm_acc_rating", rating.toUpperCase(Locale.ROOT));
        }
        if (!"active".equalsIgnoreCase(text(values.get("crm_acc_status")))) {
            reasons.add("Customer status must be active before entering a customer pool");
        } else {
            values.put("crm_acc_status", "active");
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

    private static void syncExistingPoolSnapshot(DataAccessor db, String customerId, Map<String, Object> values) {
        List<Map<String, Object>> items = safeQuery(db, "crm_customer_pool_item_common",
                Map.of("crm_cpi_account_key", customerId));
        if (items.isEmpty()) throw new IllegalStateException("Customer-pool projection is missing for " + customerId);
        HashMap<String, Object> patch = new HashMap<>();
        copyIfPresent(values, "crm_acc_code", patch, "crm_cpi_account_code");
        copyIfPresent(values, "crm_acc_name", patch, "crm_cpi_account_name");
        copyIfPresent(values, "crm_acc_industry", patch, "crm_cpi_industry");
        copyIfPresent(values, "crm_acc_phone", patch, "crm_cpi_phone");
        copyIfPresent(values, "crm_acc_rating", patch, "crm_cpi_rating");
        if (!patch.isEmpty()) db.update("crm_customer_pool_item_common",
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
        Map<String, Object> pool = db.getById("crm_customer_pool_common", poolId);
        if (pool == null) throw new IllegalArgumentException("Customer pool not found: " + poolId);
        if (!"enabled".equals(text(pool.get("crm_cp_status")))) {
            throw new IllegalStateException("Customer pool is disabled: " + poolId);
        }
        if (!CustomerPoolRules.isMember(pool.get("crm_cp_member_user_ids"), pool.get("crm_cp_admin_user_ids"), actor)) {
            throw new SecurityException("Current user is not a member of customer pool " + poolId);
        }
        return pool;
    }

    private static boolean isPoolAdministrator(DataAccessor db, String poolId, String actor) {
        Map<String, Object> pool = db.getById("crm_customer_pool_common", poolId);
        return pool != null && CustomerPoolRules.isAdministrator(pool.get("crm_cp_admin_user_ids"), actor);
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
