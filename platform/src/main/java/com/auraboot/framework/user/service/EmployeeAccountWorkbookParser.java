package com.auraboot.framework.user.service;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.user.dto.EmployeeAccountRow;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class EmployeeAccountWorkbookParser {

    private final DataFormatter dataFormatter = new DataFormatter(Locale.ROOT);

    public List<EmployeeAccountRow> parse(InputStream inputStream) {
        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new BusinessException("Workbook has no sheets");
            }
            Sheet sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(sheet.getFirstRowNum());
            Map<String, Integer> columns = readColumns(header);
            int nameColumn = requiredColumn(columns, "name", "姓名/name");
            int typeColumn = requiredColumn(columns, "type", "类型/type");
            Integer mobileColumn = columns.get("mobile");
            Integer emailColumn = columns.get("email");

            List<EmployeeAccountRow> rows = new ArrayList<>();
            for (int i = sheet.getFirstRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }
                String name = readCell(row.getCell(nameColumn));
                String type = readCell(row.getCell(typeColumn));
                if (isBlank(name) && isBlank(type)) {
                    continue;
                }
                EmployeeAccountRow accountRow = new EmployeeAccountRow();
                accountRow.setName(name);
                accountRow.setType(type);
                accountRow.setMobile(mobileColumn == null ? null : readCell(row.getCell(mobileColumn)));
                accountRow.setEmail(emailColumn == null ? null : readCell(row.getCell(emailColumn)));
                rows.add(accountRow);
            }
            return rows;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("Failed to parse employee account workbook: " + e.getMessage());
        }
    }

    private Map<String, Integer> readColumns(Row header) {
        if (header == null) {
            throw new BusinessException("Workbook header row is missing");
        }
        Map<String, Integer> columns = new HashMap<>();
        for (Cell cell : header) {
            String key = normalizeHeader(readCell(cell));
            if (key != null) {
                columns.put(key, cell.getColumnIndex());
            }
        }
        return columns;
    }

    private int requiredColumn(Map<String, Integer> columns, String key, String label) {
        Integer column = columns.get(key);
        if (column == null) {
            throw new BusinessException("Missing required column: " + label);
        }
        return column;
    }

    private String normalizeHeader(String value) {
        if (isBlank(value)) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "姓名", "name", "employee name" -> "name";
            case "类型", "type", "employee type" -> "type";
            case "手机", "手机号", "mobile", "phone", "phone number" -> "mobile";
            case "邮箱", "email", "email address" -> "email";
            default -> null;
        };
    }

    private String readCell(Cell cell) {
        if (cell == null) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            return BigDecimal.valueOf(cell.getNumericCellValue())
                    .stripTrailingZeros()
                    .toPlainString();
        }
        String value = dataFormatter.formatCellValue(cell);
        return isBlank(value) ? null : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
